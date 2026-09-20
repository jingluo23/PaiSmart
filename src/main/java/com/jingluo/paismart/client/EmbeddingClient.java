package com.jingluo.paismart.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingluo.paismart.domain.response.ActiveProviderView;
import com.jingluo.paismart.domain.response.EmbeddingApiResponse;
import com.jingluo.paismart.domain.response.EmbeddingUsageResult;
import com.jingluo.paismart.domain.response.TokenReservationBundle;
import com.jingluo.paismart.enums.UsageType;
import com.jingluo.paismart.service.ModelProviderConfigService;
import com.jingluo.paismart.service.RateLimitService;
import com.jingluo.paismart.service.UsageQuotaService;

import lombok.extern.slf4j.Slf4j;
import reactor.util.retry.Retry;

/**
 * @Author: 鲸落
 * @Date: 2026/9/18 17:22
 * @Desc: Embedding API 客户端：分批调用 OpenAI 兼容接口生成向量， 内置 Token 预扣/结算/回滚与失败重试
 */
@Slf4j
@Component
public class EmbeddingClient {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private UsageQuotaService usageQuotaService;

    @Autowired
    private ModelProviderConfigService modelProviderConfigService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 单次 API 请求携带的最大文本条数，超出该规模的输入会被拆分为多个批次依次调用
     */
    @Value("${embedding.api.batch-size:100}")
    private int batchSize;

    /**
     * 调用模型 API 生成向量的便捷重载，忽略实际 Token 消耗与模型版本
     *
     * @param texts
     *            输入文本列表
     * @param requesterId
     *            请求者标识，用于配额归属
     * @param usageType
     *            使用场景，决定适用的限流与配额策略
     * @return 与输入顺序一一对应的向量列表
     */
    public List<float[]> embed(List<String> texts, String requesterId, UsageType usageType) {
        return embedWithUsage(texts, requesterId, usageType).getVectors();
    }

    /**
     * 带用量核算的向量化调用：按批次拆分输入，每批先按估算值预扣 Token 额度， 调用成功后按 API 返回的实际用量结算，失败则回滚本批预留并中断后续批次
     *
     * @param texts
     *            输入文本列表
     * @param requesterId
     *            请求者标识，用于配额归属
     * @param usageType
     *            使用场景，决定适用的限流与配额策略
     * @return 全部向量的集合、累计 Token 消耗与当前模型版本
     */
    public EmbeddingUsageResult embedWithUsage(List<String> texts, String requesterId, UsageType usageType) {
        try {
            String normalizedRequesterId = requesterId == null || requesterId.isBlank() ? "unknown" : requesterId;

            List<float[]> all = new ArrayList<>(texts.size());
            int totalTokens = 0;
            for (int start = 0; start < texts.size(); start += batchSize) {
                int end = Math.min(start + batchSize, texts.size());
                List<String> sub = texts.subList(start, end);
                TokenReservationBundle reservation = usageType == UsageType.QUERY
                    ? rateLimitService.reserveEmbeddingQueryUsage(normalizedRequesterId, sub)
                    : rateLimitService.reserveEmbeddingUploadUsage(normalizedRequesterId, sub);

                try {
                    String response = callApiOnce(sub);
                    EmbeddingApiResponse parsedResponse = parseEmbeddingResponse(response, sub);
                    usageQuotaService.settleReservation(reservation, parsedResponse.getTotalTokens());

                    all.addAll(parsedResponse.getVectors());

                    totalTokens += parsedResponse.getTotalTokens();
                } catch (Exception e) {
                    usageQuotaService.abortReservation(reservation);

                    throw e;
                }
            }

            return new EmbeddingUsageResult(all, totalTokens, currentModelVersion());
        } catch (WebClientResponseException e) {
            // 提供详细的API响应错误信息
            throw new RuntimeException(
                String.format("向量生成失败 - API错误: HTTP %d - %s", e.getStatusCode().value(), e.getResponseBodyAsString()),
                e);
        } catch (Exception e) {
            throw new RuntimeException("向量生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用一次 OpenAI 兼容的 /embeddings 接口，瞬时错误自动重试最多 3 次， 整体超时 30 秒
     *
     * @param batch
     *            单批文本
     * @return API 原始 JSON 响应
     */
    private String callApiOnce(List<String> batch) {
        ActiveProviderView provider =
            modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", provider.getModel());
        requestBody.put("input", batch);

        if (Objects.nonNull(provider.getDimension())) {
            requestBody.put("dimension", provider.getDimension());
        }

        requestBody.put("encoding_format", "float");

        return buildClient(provider).post().uri("/embeddings").bodyValue(requestBody).retrieve()
            .bodyToMono(String.class).retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1))
                .filter(e -> e instanceof WebClientResponseException).doBeforeRetry(signal -> log
                    .warn("重试API调用 - 尝试: {}, 错误: {}", signal.totalRetries() + 1, signal.failure().getMessage())))
            .block(Duration.ofSeconds(30));
    }

    /**
     * 按提供者配置构建 WebClient：附带鉴权头，并按调用方每次获取最新配置， 避免提供者切换后仍使用旧连接
     *
     * @param provider
     *            当前激活的提供者配置
     * @return 可用的 WebClient 实例
     */
    private WebClient buildClient(ActiveProviderView provider) {
        WebClient.Builder builder = WebClient.builder()
            .baseUrl(modelProviderConfigService.normalizeOpenAiCompatibleBaseUrl(provider.getApiBaseUrl()))
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            // WebClient 的默认缓冲区大小限制（256KB）, 这里调高到 16MB
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024));

        if (StringUtils.isNotBlank(provider.getApiKey())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getApiKey());
        }

        return builder.build();
    }

    /**
     * 解析 /embeddings 接口响应：提取各条目向量与用量， 兼容 total_tokens / input_tokens 两种用量字段，均缺失时以本地估算兜底
     *
     * @param response
     *            API 原始 JSON 响应
     * @param inputTexts
     *            本批输入文本，用于用量缺失时的本地估算
     * @return 解析后的向量列表与 Token 总数
     */
    private EmbeddingApiResponse parseEmbeddingResponse(String response, List<String> inputTexts)
        throws JsonProcessingException {
        JsonNode jsonNode = objectMapper.readTree(response);
        // 兼容模式下使用data字段
        JsonNode data = jsonNode.get("data");
        if (Objects.isNull(data) || !data.isArray()) {
            throw new RuntimeException("API 响应格式错误: data 字段不存在或不是数组");
        }

        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (Objects.nonNull(embedding) && embedding.isArray()) {
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float)embedding.get(i).asDouble();
                }

                vectors.add(vector);
            }
        }

        JsonNode usage = jsonNode.path("usage");
        int totalTokens = usage.path("total_tokens").asInt(usage.path("input_tokens").asInt(0));

        return new EmbeddingApiResponse(vectors,
            totalTokens > 0 ? totalTokens : usageQuotaService.estimateEmbeddingTokens(inputTexts));
    }

    /**
     * 获取当前向量模型版本标识（provider:model:dimension）， 随提供者配置实时变化，写入向量库用于版本比对
     *
     * @return 模型版本标识字符串
     */
    public String currentModelVersion() {
        ActiveProviderView provider =
            modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);

        return provider.getProvider() + ":" + provider.getModel() + ":" + provider.getDimension();
    }
}
