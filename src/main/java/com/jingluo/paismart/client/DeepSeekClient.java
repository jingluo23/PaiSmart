package com.jingluo.paismart.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.tika.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingluo.paismart.config.AiProperties;
import com.jingluo.paismart.domain.response.ActiveProviderView;
import com.jingluo.paismart.domain.response.Generation;
import com.jingluo.paismart.domain.response.StreamUsageTracker;
import com.jingluo.paismart.domain.response.SummaryEndpoint;
import com.jingluo.paismart.domain.response.TokenReservation;
import com.jingluo.paismart.entity.SearchResult;
import com.jingluo.paismart.service.ModelProviderConfigService;
import com.jingluo.paismart.service.UsageQuotaService;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

/**
 * DeepSeek LLM 客户端
 * <p>
 * 为 generate_summary 工具提供内部摘要能力：接收知识库检索片段，
 * 以流式方式调用大模型生成结构化摘要，并按实际 token 用量结算配额。
 * 调用前先按估算值预留 token 额度，流结束后多退少补，失败时释放预留。
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 17:24
 */
@Slf4j
@Service
public class DeepSeekClient {

    @Autowired
    private UsageQuotaService usageQuotaService;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private ModelProviderConfigService modelProviderConfigService;

    private final WebClient webClient;

    private final String model;

    private final ObjectMapper objectMapper;

    /**
     * 构造客户端，读取 deepseek.* 配置作为默认（回退）端点
     *
     * @param apiUrl 默认 API 基础地址
     * @param apiKey 默认 API 密钥，可为空（空则不携带 Authorization 头）
     * @param webClient Spring 注入的 WebClient（仅用于占位，实际按 apiUrl 重建）
     * @param model  默认模型名
     */
    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl, @Value("${deepseek.api.key}") String apiKey,
        WebClient webClient, @Value("${deepseek.api.model}") String model) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);

        // 只有当 API key 不为空时才添加 Authorization header
        if (!StringUtils.isBlank(apiKey) && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        this.webClient = builder.build();
        this.model = model;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 基于知识库检索片段生成流式摘要
     * <p>
     * 流程：构建提示词 -> 估算并预留 token 额度 -> 流式调用 LLM -> 按实际用量结算。
     *
     * @param requesterId   发起请求的用户 ID，用于配额结算
     * @param topic         摘要主题，不能为空
     * @param searchResults 知识库检索片段，为空时直接返回无结果提示
     * @param onChunk       流式回调，每收到一段增量文本即推送，可为 null
     * @return 完整摘要文本
     */
    public String summarize(String requesterId, String topic, List<SearchResult> searchResults,
        Consumer<String> onChunk) {
        if (StringUtils.isBlank(topic)) {
            throw new IllegalArgumentException("摘要主题不能为空");
        }

        if (CollectionUtils.isEmpty(searchResults)) {
            String noResult = "未检索到与主题相关的知识库文档，无法生成基于知识库的摘要。";
            if (Objects.nonNull(onChunk)) {
                onChunk.accept(noResult);
            }

            return noResult;
        }

        List<Map<String, String>> messages = buildSummaryMessages(topic, searchResults);

        int estimatedPromptTokens = usageQuotaService.estimateChatTokens(messages);

        int maxCompletionTokens =
            aiProperties.getGeneration().getMaxTokens() != null ? aiProperties.getGeneration().getMaxTokens() : 1600;

        TokenReservation reservation =
            usageQuotaService.reserveLlmTokens(requesterId, estimatedPromptTokens, maxCompletionTokens);

        StreamUsageTracker usageTracker = null;
        try {
            // 优先使用运营配置的活动 LLM Provider，读取失败则回退到 deepseek.* 配置
            SummaryEndpoint summaryEndpoint = resolveSummaryEndpoint();
            Map<String, Object> request = new HashMap<>();
            request.put("model", summaryEndpoint.getModel());
            request.put("messages", messages);
            request.put("stream", true);
            request.put("stream_options", Map.of("include_usage", true));
            request.put("max_tokens", maxCompletionTokens);
            Generation gen = aiProperties.getGeneration();
            if (gen.getTemperature() != null) {
                request.put("temperature", Math.min(gen.getTemperature(), 0.3d));
            } else {
                request.put("temperature", 0.2d);
            }
            if (gen.getTopP() != null) {
                request.put("top_p", gen.getTopP());
            }

            usageTracker = new StreamUsageTracker(reservation, estimatedPromptTokens);
            String summary = executeSummaryStream(summaryEndpoint, request, usageTracker, onChunk);

            return summary;
        } catch (Exception e) {
            if (Objects.isNull(usageTracker) || !usageTracker.isSettled()) {
                usageQuotaService.abortReservation(reservation);
            }

            throw new RuntimeException("生成知识库摘要失败", e);
        }
    }

    /**
     * 执行流式摘要请求并阻塞等待结果
     * <p>
     * 通过 WebClient 订阅 SSE 数据流，聚合增量内容；整体设有 90 秒超时，
     * 超时/中断/异常时主动取消订阅并结算已产生的 token 用量。
     *
     * @param summaryEndpoint 目标 LLM 端点（WebClient + 模型名 + Provider 名）
     * @param request         OpenAI 兼容的 chat/completions 请求体
     * @param usageTracker    token 用量追踪器，流结束后用于结算配额
     * @param onChunk         流式回调，透传给底层 chunk 处理逻辑
     * @return 聚合后的完整摘要文本
     */
    private String executeSummaryStream(SummaryEndpoint summaryEndpoint, Map<String, Object> request,
        StreamUsageTracker usageTracker, Consumer<String> onChunk) {
        CompletableFuture<String> summaryFuture = new CompletableFuture<>();
        long startedAt = System.currentTimeMillis();
        AtomicBoolean firstChunkLogged = new AtomicBoolean(false);
        AtomicInteger chunkCount = new AtomicInteger(0);
        Consumer<String> chunkConsumer = chunk -> {
            if (chunk != null && !chunk.isEmpty()) {
                int currentChunkCount = chunkCount.incrementAndGet();
                if (firstChunkLogged.compareAndSet(false, true)) {
                    log.info("generate_summary 内部摘要收到首个流式 chunk: provider={}, model={}, elapsedMs={}, chunkChars={}",
                        summaryEndpoint.getProvider(), summaryEndpoint.getModel(),
                        System.currentTimeMillis() - startedAt, chunk.length());
                }
            }
            if (onChunk != null) {
                onChunk.accept(chunk);
            }
        };

        Disposable subscription = summaryEndpoint.getWebClient().post().uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(request).retrieve().bodyToFlux(String.class)
            .subscribe(chunk -> processChunk(chunk, usageTracker, chunkConsumer), error -> {
                settleUsage(usageTracker);
                summaryFuture.completeExceptionally(error);
            }, () -> {
                settleUsage(usageTracker);

                summaryFuture.complete(usageTracker.getResponseContent().toString().trim());
            });

        try {
            String summary = summaryFuture.get(90, TimeUnit.SECONDS);
            if (summary == null || summary.isBlank()) {
                throw new IllegalStateException("DeepSeek 摘要流未返回有效内容");
            }
            return summary;
        } catch (TimeoutException e) {
            subscription.dispose();
            settleUsage(usageTracker);

            throw new RuntimeException("DeepSeek 摘要流式响应超时", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            subscription.dispose();
            settleUsage(usageTracker);

            throw new RuntimeException("DeepSeek 摘要流式响应被中断", e);
        } catch (ExecutionException e) {
            subscription.dispose();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            throw new RuntimeException("DeepSeek 摘要流式响应失败", cause);
        }
    }

    /**
     * 结算 token 配额（保证只结算一次）
     * <p>
     * 若流式响应未上报 usage，则 prompt 按估算值、completion 按聚合内容长度折算。
     *
     * @param usageTracker 用量追踪器
     */
    private void settleUsage(StreamUsageTracker usageTracker) {
        if (Objects.isNull(usageTracker) || usageTracker.isSettled()) {
            return;
        }

        usageTracker.setSettled(true);
        int actualPromptTokens = usageTracker.getPromptTokens() > 0 ? usageTracker.getPromptTokens()
            : usageTracker.getEstimatedPromptTokens();
        int actualCompletionTokens = usageTracker.getCompletionTokens() > 0 ? usageTracker.getCompletionTokens()
            : usageQuotaService.estimateTextTokens(usageTracker.getResponseContent().toString());

        usageQuotaService.settleReservation(usageTracker.getReservation(), actualPromptTokens + actualCompletionTokens);
    }

    /**
     * 解析单个 SSE 原始数据块：提取 usage 统计与增量文本
     * <p>
     * 单个数据块可能包含多行 data: 载荷，逐行解析；解析失败仅告警，不中断整个流。
     *
     * @param rawChunk     SSE 原始文本块
     * @param usageTracker 用量追踪器，写入流式上报的 prompt/completion tokens
     * @param onChunk      增量文本回调
     */
    private void processChunk(String rawChunk, StreamUsageTracker usageTracker, Consumer<String> onChunk) {
        try {
            for (String chunk : extractPayloads(rawChunk)) {
                if ("[DONE]".equals(chunk)) {
                    continue;
                }

                JsonNode node = objectMapper.readTree(chunk);
                JsonNode usageNode = node.path("usage");
                if (usageNode.isObject()) {
                    usageTracker.setPromptTokens(usageNode.path("prompt_tokens").asInt(usageTracker.getPromptTokens()));
                    usageTracker.setCompletionTokens(
                        usageNode.path("completion_tokens").asInt(usageTracker.getCompletionTokens()));
                }

                String content = node.path("choices").path(0).path("delta").path("content").asText("");

                if (!content.isEmpty()) {
                    usageTracker.getResponseContent().append(content);
                    onChunk.accept(content);
                }
            }
        } catch (Exception e) {
            log.warn("处理数据块时出错: {}", e.getMessage(), e);
        }
    }

    /**
     * 从原始数据块中提取 JSON 载荷列表
     * <p>
     * 跳过空行与 SSE 注释行（以":"开头），剥离 "data:" 前缀与 [DONE] 标记；
     * 若整块不含标准 SSE 格式，则视为整体作为一个载荷返回（兼容非流式网关）。
     *
     * @param rawChunk SSE 原始文本块
     * @return JSON 字符串载荷列表
     */
    private List<String> extractPayloads(String rawChunk) {
        List<String> payloads = new ArrayList<>();
        if (StringUtils.isBlank(rawChunk)) {
            return payloads;
        }

        String trimmed = rawChunk.trim();
        for (String line : trimmed.split("\\r?\\n")) {
            String payload = line.trim();
            if (payload.isEmpty() || payload.startsWith(":")) {
                continue;
            }

            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }

            if (!payload.isEmpty()) {
                payloads.add(payload);
            }
        }

        if (payloads.isEmpty()) {
            payloads.add(trimmed);
        }

        return payloads;
    }

    /**
     * 解析本次摘要实际使用的 LLM 端点
     * <p>
     * 优先读取运营侧配置的活动 LLM Provider（ModelProviderConfigService），
     * 读取失败时回退到 deepseek.* 本地配置，保证摘要能力可用。
     *
     * @return 摘要端点（WebClient、模型名、Provider 名）
     */
    private SummaryEndpoint resolveSummaryEndpoint() {
        try {
            ActiveProviderView provider =
                modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);

            WebClient.Builder builder = WebClient.builder()
                .baseUrl(modelProviderConfigService.normalizeOpenAiCompatibleBaseUrl(provider.getApiBaseUrl()));
            if (!StringUtils.isBlank(provider.getApiKey())) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getApiKey());
            }

            return new SummaryEndpoint(builder.build(), provider.getModel(), provider.getProvider());
        } catch (Exception exception) {
            log.warn("无法读取活动 LLM Provider，generate_summary 内部摘要回退到 deepseek.* 配置: {}", exception.getMessage());

            return new SummaryEndpoint(webClient, model, "deepseek");
        }
    }

    /**
     * 构建摘要请求的消息列表（system 提示词 + user 主题与知识库片段）
     *
     * @param topic         摘要主题
     * @param searchResults 知识库检索片段
     * @return OpenAI 格式的 messages
     */
    private List<Map<String, String>> buildSummaryMessages(String topic, List<SearchResult> searchResults) {
        List<Map<String, String>> messages = new ArrayList<>();

        messages.add(Map.of("role", "system", "content", "你是 generate_summary 工具内部使用的知识库摘要模型。"
            + "只基于提供的知识库片段生成结构化摘要，不要发起工具调用，不要把自己当作外层 ReAct 循环。" + "输出应包含：核心结论、关键依据、可执行建议或待确认问题。"));

        messages.add(
            Map.of("role", "user", "content", "主题：" + topic + "\n\n知识库片段：\n" + buildSummaryContext(searchResults)));

        return messages;
    }

    /**
     * 将检索片段拼装为带来源标识（fileMd5/chunkId/page）的上下文文本
     *
     * @param searchResults 知识库检索片段
     * @return 拼装后的上下文文本
     */
    private String buildSummaryContext(List<SearchResult> searchResults) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < searchResults.size(); i++) {
            SearchResult result = searchResults.get(i);
            context.append("[").append(i + 1).append("] ");
            if (!StringUtils.isBlank(result.getFileName())) {
                context.append("文件：").append(result.getFileName()).append("，");
            }

            context.append("fileMd5=").append(result.getFileMd5()).append("，chunkId=").append(result.getChunkId());

            if (Objects.nonNull(result.getPageNumber())) {
                context.append("，page=").append(result.getPageNumber());
            }

            context.append("\n")
                .append(limitText(
                    result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent(),
                    1800))
                .append("\n\n");
        }

        return context.toString();
    }

    /**
     * 截断文本到指定长度，超出部分以 "..." 结尾
     *
     * @param text     原文本
     * @param maxChars 最大字符数
     * @return 截断后的文本
     */
    private String limitText(String text, int maxChars) {
        if (StringUtils.isBlank(text)) {
            return "";
        }

        if (text.length() <= maxChars) {
            return text;
        }

        return text.substring(0, maxChars) + "...";
    }
}
