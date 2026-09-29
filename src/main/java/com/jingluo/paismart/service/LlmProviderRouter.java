package com.jingluo.paismart.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingluo.paismart.config.AiProperties;
import com.jingluo.paismart.domain.response.ActiveProviderView;
import com.jingluo.paismart.domain.response.AgentTool;
import com.jingluo.paismart.domain.response.Generation;
import com.jingluo.paismart.domain.response.Prompt;
import com.jingluo.paismart.domain.response.ReActStreamAccumulator;
import com.jingluo.paismart.domain.response.ReActTurn;
import com.jingluo.paismart.domain.response.StreamHandle;
import com.jingluo.paismart.domain.response.TokenReservationBundle;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

/**
 * @Author: 鲸落
 * @Date: 2026/9/28 15:35
 * @Desc: LLM 供应商路由服务：按当前生效的模型供应商配置组装 OpenAI 兼容请求， 提供流式 ReAct 回合调用（含 token 预留/结算）、 SSE 数据块解析与 prompt token 估算能力
 */
@Slf4j
@Service
public class LlmProviderRouter {

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private ModelProviderConfigService modelProviderConfigService;

    @Autowired
    private UsageQuotaService usageQuotaService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RateLimitService rateLimitService;

    /**
     * ReAct 上下文中最多携带的历史消息条数
     */
    private static final int REACT_HISTORY_MAX_MESSAGES = 6;

    /**
     * 单条历史消息内容的最大保留字符数，防止旧对话撑爆 prompt
     */
    private static final int REACT_HISTORY_MAX_CONTENT_CHARS = 800;

    /**
     * 构建 ReAct 起始消息序列：system（规则 + 强制检索原则 + 可跳过检索白名单 + 回答异常处理约定）、 预置检索上下文、截断后的近期历史、本轮用户消息
     *
     * @param userMessage
     *            用户消息原文
     * @param context
     *            预置检索上下文（无则写入提示文案）
     * @param history
     *            历史对话消息
     * @param feedbackGuidance
     *            用户近期反馈偏好指引（可为空）
     * @return OpenAI 消息结构列表
     */
    public List<Map<String, Object>> buildReActMessages(String userMessage, String context,
        List<Map<String, String>> history, String feedbackGuidance) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Prompt promptCfg = aiProperties.getPrompt();

        StringBuilder sysBuilder = new StringBuilder();
        if (StringUtils.isNotBlank(promptCfg.getRules())) {
            sysBuilder.append(promptCfg.getRules()).append("\n\n");
        }
        sysBuilder.append(
            "本系统是「知识库优先」的问答助手：你的首要职责是基于本系统已收录的资料回答用户。除非命中下方明确的白名单，否则**每一个用户问题都必须先调用 search_knowledge**，再基于检索结果作答。\n\n")
            .append("强制检索原则（默认行为）：\n")
            .append(
                "1. 默认调用 search_knowledge：只要问题涉及任何实体、名称、缩写、产品、项目、术语、流程、功能、实现、背景、对比、引用，或包含「这/它/该/上述/这个/那个」等上下文指代，无论你是否自认为已知答案，都必须先检索，不要等用户说「查知识库」。\n")
            .append("2. 构造 query 时严格保留用户原话中的核心名词、缩写和限定词，禁止替换为泛化关键词；必要时可在同一次 query 中合并原句与等价改写。\n")
            .append("3. 用户要求整理、总结、归纳、提炼知识库内容时，先用 search_knowledge 圈定材料，再调用 generate_summary 生成总结。\n\n")
            .append("可以跳过 search_knowledge 的白名单（必须严格匹配其一，否则一律检索）：\n").append("- 纯打招呼或寒暄（你好/谢谢/再见等）；\n")
            .append("- 纯翻译请求（把 X 翻译为 Y），且不涉及本系统术语；\n").append("- 与本系统材料无关的纯创作请求（写诗、写段子等）；\n")
            .append("- 通用编程语法、数学计算等完全不依赖任何专有信息的常识题；\n").append("- 用户在本轮明确表示「不要查知识库 / 直接回答」。\n\n").append("回答与异常处理：\n")
            .append("- 只要 search_knowledge 返回了片段，必须基于片段作答并按来源编号标注，禁止回答「知识库暂无相关信息」。\n")
            .append("- 只有工具明确返回零片段时，才说明暂无相关材料并提示用户补充线索。\n").append("- 工具失败时根据错误信息决定下一步（重试 / 换 query / 继续推理），不要直接中断。\n")
            .append("- 如需记录反馈或查看知识库统计，通过 tool_calls 调用对应工具。\n").append("拿到 tool 结果后继续推理并给出最终回答。\n\n");
        if (feedbackGuidance != null && !feedbackGuidance.isBlank()) {
            sysBuilder.append(feedbackGuidance.trim()).append("\n\n");
        }

        String refStart = StringUtils.isNotBlank(promptCfg.getRefStart()) ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = StringUtils.isNotBlank(promptCfg.getRefEnd()) ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");
        if (StringUtils.isNotBlank(context)) {
            sysBuilder.append(context);
        } else {
            sysBuilder.append(StringUtils.isNotBlank(promptCfg.getNoResultText()) ? promptCfg.getNoResultText()
                : "（本轮无预置检索结果，可按需调用工具）").append("\n");
        }
        sysBuilder.append(refEnd);

        messages.add(newMessage("system", sysBuilder.toString()));
        if (CollectionUtils.isNotEmpty(history)) {
            int start = Math.max(0, history.size() - REACT_HISTORY_MAX_MESSAGES);
            for (Map<String, String> message : history.subList(start, history.size())) {
                String role = message.get("role");
                String content = message.get("content");
                if (StringUtils.isBlank(role) || StringUtils.isBlank(content)) {
                    continue;
                }

                if ("user".equals(role) || "assistant".equals(role) || "system".equals(role)) {
                    messages.add(newMessage(role, limitText(content, REACT_HISTORY_MAX_CONTENT_CHARS)));
                }
            }
        }

        messages.add(newMessage("user", userMessage));

        return messages;
    }

    /**
     * 按最大字符数截断文本，超长时以省略号结尾
     *
     * @param text
     *            原文本
     * @param maxChars
     *            最大保留字符数
     * @return 截断后的文本
     */
    private String limitText(String text, int maxChars) {
        if (StringUtils.isBlank(text) || text.length() <= maxChars) {
            return text;
        }

        return text.substring(0, Math.max(maxChars, 0)) + "...";
    }

    /**
     * 构造一条 role/content 形式的消息（content 缺省为空串）
     *
     * @param role
     *            消息角色（system/user/assistant）
     * @param content
     *            消息内容
     * @return 消息 Map
     */
    private Map<String, Object> newMessage(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", StringUtils.isBlank(content) ? "" : content);

        return message;
    }

    /**
     * 发起一轮流式 ReAct 模型调用：预留 token 预算后向当前生效供应商请求 /chat/completions， 正文体通过 onChunk 逐块外抛，回合结束（或失败）时结算实际用量并回调 onComplete（或
     * onError）。
     * <p>
     * 订阅建立前抛错时回滚预留；取消句柄会在 dispose 时补做结算， 保证"正常完成、失败、取消"三条路径都恰好结算一次。
     *
     * @param requesterId
     *            请求方用户 ID（用于配额归属）
     * @param messages
     *            对话消息
     * @param tools
     *            允许模型调用的工具（为空表示收尾回合，不下发 tools 参数）
     * @param maxCompletionTokens
     *            本回合 completion token 上限
     * @param onChunk
     *            正文块回调
     * @param onError
     *            流失败回调
     * @param onComplete
     *            回合完成回调
     * @return 流句柄，可用于取消订阅与结算
     */
    public StreamHandle streamReActTurn(String requesterId, List<Map<String, Object>> messages, List<AgentTool> tools,
        int maxCompletionTokens, Consumer<String> onChunk, Consumer<Throwable> onError,
        Consumer<ReActTurn> onComplete) {
        ActiveProviderView provider =
            modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);

        Map<String, Object> request =
            buildReActRequest(provider.getModel(), messages, tools, maxCompletionTokens, true);

        int estimatedPromptTokens =
            estimateObjectMessagesTokens(messages) + (CollectionUtils.isEmpty(tools) ? 0 : estimateToolsTokens(tools));

        TokenReservationBundle reservation =
            rateLimitService.reserveLlmUsage(requesterId, estimatedPromptTokens, Math.max(maxCompletionTokens, 1));
        ReActStreamAccumulator accumulator = new ReActStreamAccumulator(reservation, estimatedPromptTokens);

        try {
            Disposable subscription = buildClient(provider).post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request).retrieve().bodyToFlux(String.class)
                .subscribe(chunk -> processReActStreamChunk(chunk, accumulator, onChunk), error -> {
                    logProviderError("ReAct 流式回合调用失败", error);

                    settleReActStreamUsage(accumulator);

                    onError.accept(error);
                }, () -> {
                    settleReActStreamUsage(accumulator);
                    ReActTurn turn = accumulator.toTurn();

                    onComplete.accept(turn);
                });

            return new StreamHandle(subscription, () -> settleReActStreamUsage(accumulator));
        } catch (Exception exception) {
            usageQuotaService.abortReservation(reservation);
            throw exception;
        }
    }

    /**
     * 结算一轮流式调用的 token 用量：上游上报了 usage 则按实际值结算， 否则 completion 侧按正文加 assistant 消息的估算值兜底；幂等，只结算一次
     *
     * @param accumulator
     *            流式累积器
     */
    private void settleReActStreamUsage(ReActStreamAccumulator accumulator) {
        if (Objects.isNull(accumulator) || !accumulator.markSettled()) {
            return;
        }

        int actualPromptTokens =
            accumulator.getPromptTokens() > 0 ? accumulator.getPromptTokens() : accumulator.getEstimatedPromptTokens();

        int actualCompletionTokens = accumulator.getCompletionTokens() > 0 ? accumulator.getCompletionTokens()
            : usageQuotaService.estimateTextTokens(accumulator.getContent().toString())
                + estimateObjectMessagesTokens(List.of(accumulator.assistantMessage()));

        usageQuotaService.settleReservation(accumulator.getReservation(), actualPromptTokens + actualCompletionTokens);
    }

    /**
     * 记录供应商调用错误日志：HTTP 类错误额外输出状态码与响应体
     *
     * @param message
     *            日志前缀说明
     * @param error
     *            原始错误
     */
    private void logProviderError(String message, Throwable error) {
        if (error instanceof WebClientResponseException responseException) {
            log.warn("{}: status={}, body={}", message, responseException.getStatusCode(),
                responseException.getResponseBodyAsString(), responseException);

            return;
        }

        log.warn("{}: {}", message, error.getMessage(), error);
    }

    /**
     * 处理一帧上游 SSE 原始数据：解析出 usage、finish_reason、正文、 思考内容与 tool_call 增量并写入累积器，正文块同时外抛给回调； 单帧解析失败只记日志，不影响后续数据块
     *
     * @param rawChunk
     *            原始数据块（可能包含多行 data: 行）
     * @param accumulator
     *            流式累积器
     * @param onChunk
     *            正文块回调
     */
    private void processReActStreamChunk(String rawChunk, ReActStreamAccumulator accumulator,
        Consumer<String> onChunk) {
        try {
            for (String chunk : extractPayloads(rawChunk)) {
                if ("[DONE]".equals(chunk)) {
                    continue;
                }

                JsonNode node = objectMapper.readTree(chunk);
                JsonNode usageNode = node.path("usage");
                if (usageNode.isObject()) {
                    accumulator.setPromptTokens(usageNode.path("prompt_tokens").asInt(accumulator.getPromptTokens()));
                    accumulator.setCompletionTokens(
                        usageNode.path("completion_tokens").asInt(accumulator.getCompletionTokens()));
                }

                JsonNode choiceNode = node.path("choices").path(0);
                if (!choiceNode.isObject()) {
                    continue;
                }

                JsonNode finishReasonNode = choiceNode.path("finish_reason");
                if (!finishReasonNode.isMissingNode() && !finishReasonNode.isNull()) {
                    String finishReason = finishReasonNode.asText("");
                    if (!finishReason.isBlank()) {
                        accumulator.setFinishReason(finishReason);
                    }
                }

                JsonNode delta = choiceNode.path("delta");
                String reasoningContent = delta.path("reasoning_content").asText("");
                if (!reasoningContent.isEmpty()) {
                    accumulator.getReasoningContent().append(reasoningContent);
                }

                String content = delta.path("content").asText("");
                if (!content.isEmpty()) {
                    accumulator.getContent().append(content);
                    onChunk.accept(content);
                }

                JsonNode toolCallsNode = delta.path("tool_calls");
                if (toolCallsNode.isArray()) {
                    for (JsonNode toolCallDelta : toolCallsNode) {
                        accumulator.appendToolCallDelta(toolCallDelta);
                    }
                }
            }
        } catch (Exception exception) {
            log.warn("处理 ReAct 流式响应数据块失败: {}", exception.getMessage(), exception);
        }
    }

    /**
     * 从原始数据块中提取 SSE 有效载荷：按行拆分，跳过空行与注释行（以冒号开头）， 剥离 data: 前缀；整块不含任何行结构时按整块兜底返回
     *
     * @param rawChunk
     *            原始数据块
     * @return 有效载荷列表（JSON 文本或 [DONE]）
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
     * 按供应商配置构建 WebClient：规范化 OpenAI 兼容 base URL，配置了 API Key 时附带 Bearer 认证头
     *
     * @param provider
     *            当前生效的模型供应商
     * @return WebClient 实例
     */
    private WebClient buildClient(ActiveProviderView provider) {
        WebClient.Builder builder = WebClient.builder()
            .baseUrl(modelProviderConfigService.normalizeOpenAiCompatibleBaseUrl(provider.getApiBaseUrl()));
        if (StringUtils.isNotBlank(provider.getApiKey())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getApiKey());
        }

        return builder.build();
    }

    /**
     * 估算工具定义占用的 prompt token：累加名称、描述与参数 Schema 文本的估算值， Schema 序列化失败时按每个 80 token 兜底
     *
     * @param tools
     *            工具列表
     * @return 估算 token 数
     */
    private int estimateToolsTokens(List<AgentTool> tools) {
        int tokens = 0;
        for (AgentTool tool : tools) {
            tokens += usageQuotaService.estimateTextTokens(tool.getName());
            tokens += usageQuotaService.estimateTextTokens(tool.getDescription());
            try {
                tokens += usageQuotaService.estimateTextTokens(objectMapper.writeValueAsString(tool.getParameters()));
            } catch (Exception ignored) {
                tokens += 80;
            }
        }

        return tokens;
    }

    /**
     * 估算消息序列占用的 prompt token：逐条累加角色、内容、思考内容与 tool_calls 的估算值， tool_calls 序列化失败时按每个 128 token 兜底，总数至少为 1
     *
     * @param messages
     *            消息序列
     * @return 估算 token 数
     */
    private int estimateObjectMessagesTokens(List<Map<String, Object>> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return 0;
        }

        int tokens = 0;
        for (Map<String, Object> message : messages) {
            tokens += 8;
            tokens += usageQuotaService.estimateTextTokens(String.valueOf(message.getOrDefault("role", "")));
            tokens += usageQuotaService.estimateTextTokens(String.valueOf(message.getOrDefault("content", "")));

            Object reasoningContent = message.get("reasoning_content");
            if (Objects.nonNull(reasoningContent)) {
                tokens += usageQuotaService.estimateTextTokens(String.valueOf(reasoningContent));
            }

            Object toolCalls = message.get("tool_calls");
            if (Objects.nonNull(toolCalls)) {
                try {
                    tokens += usageQuotaService.estimateTextTokens(objectMapper.writeValueAsString(toolCalls));
                } catch (Exception ignored) {
                    tokens += 128;
                }
            }

            Object toolCallId = message.get("tool_call_id");
            if (Objects.nonNull(toolCallId)) {
                tokens += usageQuotaService.estimateTextTokens(String.valueOf(toolCallId));
            }
        }

        return Math.max(tokens, 1);
    }

    /**
     * 组装 OpenAI 兼容的 /chat/completions 请求体：模型、消息、流式开关、 token 上限、生成参数（temperature/top_p）以及可选的 tools 定义
     *
     * @param model
     *            模型名称
     * @param messages
     *            对话消息
     * @param tools
     *            工具列表（为空时不携带 tools 字段）
     * @param maxCompletionTokens
     *            completion token 上限
     * @param stream
     *            是否流式请求（流式时附带 include_usage 选项）
     * @return 请求体
     */
    private Map<String, Object> buildReActRequest(String model, List<Map<String, Object>> messages,
        List<AgentTool> tools, int maxCompletionTokens, boolean stream) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", stream);
        request.put("max_tokens", Math.max(maxCompletionTokens, 1));
        if (stream) {
            request.put("stream_options", Map.of("include_usage", true));
        }

        Generation gen = aiProperties.getGeneration();
        if (Objects.nonNull(gen.getTemperature())) {
            request.put("temperature", gen.getTemperature());
        }

        if (Objects.nonNull(gen.getTopP())) {
            request.put("top_p", gen.getTopP());
        }

        if (CollectionUtils.isNotEmpty(tools)) {
            request.put("tools", buildOpenAiTools(tools));
            request.put("tool_choice", "auto");
        }

        return request;
    }

    /**
     * 将内部工具定义转换为 OpenAI tools 参数结构（type=function + function 对象）
     *
     * @param tools
     *            内部工具定义
     * @return OpenAI tools 参数
     */
    private List<Map<String, Object>> buildOpenAiTools(List<AgentTool> tools) {
        List<Map<String, Object>> openAiTools = new ArrayList<>();
        for (AgentTool tool : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            function.put("parameters", tool.getParameters());

            Map<String, Object> toolSchema = new LinkedHashMap<>();
            toolSchema.put("type", "function");
            toolSchema.put("function", function);
            openAiTools.add(toolSchema);
        }

        return openAiTools;
    }
}
