package com.jingluo.paismart.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingluo.paismart.domain.response.AgentTool;
import com.jingluo.paismart.domain.response.ExecutedToolResult;
import com.jingluo.paismart.domain.response.GenerationSnapshot;
import com.jingluo.paismart.domain.response.ReActTurn;
import com.jingluo.paismart.domain.response.ReferenceInfo;
import com.jingluo.paismart.domain.response.StreamCompletion;
import com.jingluo.paismart.domain.response.StreamHandle;
import com.jingluo.paismart.domain.response.ToolCallDecision;
import com.jingluo.paismart.domain.response.ToolExecutionResult;
import com.jingluo.paismart.entity.SearchResult;
import com.jingluo.paismart.exception.RateLimitExceededException;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 14:27
 * @Desc: 聊天核心处理服务：接收 WebSocket 聊天消息后驱动 ReAct 决策循环（检索/摘要等工具按需调用）， 流式推送生成内容、维护引用编号映射，并在收尾时将问答事务性落 MySQL 与 Redis；
 *        同时提供停止生成、限流检查与生成任务状态清理能力
 */
@Slf4j
@Service
public class ChatHandler {

    @Autowired
    private ChatGenerationStateService chatGenerationStateService;

    @Autowired
    private ChatSessionRegistry chatSessionRegistry;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LlmProviderRouter llmProviderRouter;

    @Autowired
    private AgentToolRegistry agentToolRegistry;

    /**
     * 用于存储每次生成任务的引用映射：generationId -> {referenceNumber -> detail}
     */
    private final Map<String, Map<Integer, ReferenceInfo>> generationReferenceMappings = new ConcurrentHashMap<>();

    /**
     * 用于标记已经取消的生成任务，防止后续又被落成 completed
     */
    private final ConcurrentHashMap.KeySetView<String, Boolean> cancelledGenerations = ConcurrentHashMap.newKeySet();

    /**
     * 停止标志 - 简单方案
     */
    private final Map<String, Boolean> stopFlags = new ConcurrentHashMap<>();

    /**
     * 用于持有正在进行中的 LLM 流，支持主动取消上游请求
     */
    private final Map<String, StreamHandle> activeStreams = new ConcurrentHashMap<>();

    /**
     * 用于跟踪每次生成任务的响应完成状态
     */
    private final Map<String, CompletableFuture<String>> responseFutures = new ConcurrentHashMap<>();

    /**
     * 用于存储每次生成任务的完整响应
     */
    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();

    private final ThreadPoolTaskExecutor chatMonitorExecutor;

    /**
     * ReAct 循环最大轮数：超过后强制注入"不要再调用工具"的收尾指令
     */
    private static final int MAX_REACT_ROUNDS = 4;

    /**
     * 单轮模型回合的 completion token 上限
     */
    private static final int REACT_MAX_COMPLETION_TOKENS = 2000;

    /**
     * 单次生成任务（含所有 ReAct 轮次）的整体超时时间（秒）
     */
    private static final int GENERATION_COMPLETION_TIMEOUT_SECONDS = 120;

    /**
     * 单次生成任务内允许执行的工具调用总数上限，防止模型循环调用工具
     */
    private static final int MAX_REACT_TOOL_CALLS = 8;

    /**
     * 引用详情中匹配片段的最大保留长度（字符）
     */
    private static final int MAX_MATCHED_CHUNK_LEN = 800;

    /**
     * 引用详情中证据摘要的最大保留长度（字符）
     */
    private static final int MAX_EVIDENCE_SNIPPET_LEN = 160;

    /**
     * 构造方法：注入线程池执行器
     *
     * @param chatMonitorExecutor
     *            线程池执行器
     */
    public ChatHandler(@Qualifier("chatMonitorExecutor") ThreadPoolTaskExecutor chatMonitorExecutor) {
        this.chatMonitorExecutor = chatMonitorExecutor;
    }

    /**
     * 查询指定生成任务中某个引用编号的详情： 内存映射未命中时回退到 Redis 中持久化的生成快照并重建映射
     *
     * @param generationId
     *            生成任务 ID
     * @param referenceNumber
     *            引用编号（回答文本中的上标序号）
     * @return 引用详情，任务或编号不存在时为 null
     */
    public ReferenceInfo getReferenceDetail(String generationId, int referenceNumber) {
        Map<Integer, ReferenceInfo> referenceMapping = generationReferenceMappings.get(generationId);
        if (CollectionUtils.isEmpty(referenceMapping)) {
            referenceMapping =
                chatGenerationStateService.getGeneration(generationId).map(GenerationSnapshot::getReferenceMappings)
                    .filter(mappings -> !CollectionUtils.isEmpty(mappings)).map(this::toReferenceInfoMap).orElse(null);
        }

        if (CollectionUtils.isEmpty(referenceMapping)) {
            return null;
        }

        ReferenceInfo detail = referenceMapping.get(referenceNumber);
        if (Objects.isNull(detail)) {
            return null;
        }

        return detail;
    }

    /**
     * 将 Redis 中序列化的引用映射（编号字符串 -> 字段 Map）还原为强类型结构， 数值字段按实际类型转换，无法解析的编号会被跳过
     *
     * @param serializedMappings
     *            序列化的引用映射
     * @return 强类型引用映射（编号 -> 引用详情）
     */
    private Map<Integer, ReferenceInfo> toReferenceInfoMap(Map<String, Map<String, Object>> serializedMappings) {
        Map<Integer, ReferenceInfo> referenceMap = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : serializedMappings.entrySet()) {
            Map<String, Object> item = entry.getValue();
            referenceMap.put(Integer.parseInt(entry.getKey()),
                new ReferenceInfo((String)item.get("fileMd5"), (String)item.get("fileName"),
                    item.get("pageNumber") instanceof Number number ? number.intValue() : null,
                    (String)item.get("anchorText"), (String)item.get("retrievalMode"),
                    (String)item.get("retrievalLabel"), (String)item.get("retrievalQuery"),
                    (String)item.get("matchedChunkText"), (String)item.get("evidenceSnippet"),
                    item.get("score") instanceof Number number ? number.doubleValue() : null,
                    item.get("chunkId") instanceof Number number ? number.intValue() : null));
        }

        return referenceMap;
    }

    /**
     * 停止用户正在进行的生成任务：置取消标志、中断上游流订阅、 异常完成响应 Future，并向前端推送 stop 通知
     * <p>
     * generationId 为空时自动回退到该用户当前的活动生成任务。
     *
     * @param userId
     *            用户 ID
     * @param generationId
     *            生成任务 ID，可为空（按用户活动任务兜底）
     */
    public void stopResponse(String userId, String generationId) {
        String resolvedGenerationId = generationId;
        if (StringUtils.isBlank(resolvedGenerationId)) {
            resolvedGenerationId = chatGenerationStateService.getActiveGenerationForUser(userId)
                .map(GenerationSnapshot::getGenerationId).orElse(null);
        }

        if (resolvedGenerationId == null || resolvedGenerationId.isBlank()) {
            log.warn("收到停止请求但未找到活动生成任务，用户ID: {}", userId);

            return;
        }

        // 归属校验：仅允许停止自己的生成任务，防止已认证用户越权停止他人任务
        if (chatGenerationStateService.getGenerationForUser(resolvedGenerationId, userId).isEmpty()) {
            log.warn("生成任务不存在或不属于当前用户，拒绝停止，用户ID: {}，generationId: {}", userId, resolvedGenerationId);

            return;
        }
        final String targetGenerationId = resolvedGenerationId;

        // 设置停止标志
        cancelledGenerations.add(targetGenerationId);
        stopFlags.put(targetGenerationId, true);
        chatGenerationStateService.markCancelled(targetGenerationId);
        StreamHandle streamHandle = activeStreams.get(targetGenerationId);
        if (Objects.nonNull(streamHandle)) {
            streamHandle.cancel();
        }

        CompletableFuture<String> responseFuture = responseFutures.get(targetGenerationId);
        if (Objects.nonNull(responseFuture) && !responseFuture.isDone()) {
            responseFuture.completeExceptionally(new CancellationException("响应已停止"));
        }

        chatSessionRegistry.sendJsonToUser(userId, Map.of("type", "stop", "generationId", targetGenerationId, "message",
            "响应已停止", "timestamp", System.currentTimeMillis(), "date", java.time.Instant.now().toString()));
    }

    /**
     * 处理一条用户聊天消息：限流校验、创建会话与生成任务、发送 start 通知， 随后把 ReAct 循环提交到独立线程池异步执行，避免阻塞 WebSocket 处理线程
     * <p>
     * 线程池打满或执行前抛错时，就地标记任务失败并向前端推送错误与中断通知。
     *
     * @param userId
     *            用户 ID
     * @param userMessage
     *            用户消息原文
     * @param session
     *            WebSocket 会话（当前仅透传，消息推送统一走 ChatSessionRegistry）
     */
    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        String conversationId = null;
        String generationId = null;
        try {
            rateLimitService.checkChatByUser(userId);

            // 1. 获取或创建会话 ID
            conversationId = getOrCreateConversationId(userId);

            conversationService.ensureConversationSession(Long.parseLong(userId), conversationId, userMessage);

            GenerationSnapshot generation =
                chatGenerationStateService.createGeneration(userId, conversationId, userMessage);
            generationId = generation.getGenerationId();
            final String finalConversationId = conversationId;
            final String finalGenerationId = generationId;

            sendGenerationStart(userId, finalGenerationId, finalConversationId);

            // 为当前生成任务创建响应构建器
            responseBuilders.put(finalGenerationId, new StringBuilder());
            // 创建一个CompletableFuture来跟踪响应完成状态
            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            responseFutures.put(finalGenerationId, responseFuture);

            // 2. 获取对话历史
            List<Map<String, String>> history = getConversationHistory(conversationId);

            // 3. 异步执行 ReAct 决策循环：模型按需返回 tool_calls，避免在 WebSocket 处理线程上阻塞 90s+ 的工具流
            try {
                chatMonitorExecutor.execute(() -> runReActLoopSafely(userId, userMessage, finalConversationId,
                    finalGenerationId, history, responseFuture));
            } catch (RejectedExecutionException ex) {
                log.warn("聊天处理线程池已满，generationId: {}", finalGenerationId);

                RuntimeException busyException = new RuntimeException("系统繁忙，请稍后重试");

                chatGenerationStateService.markFailed(finalGenerationId, busyException.getMessage());

                handleError(userId, finalGenerationId, busyException);

                sendCompletionNotification(userId, finalGenerationId, finalConversationId, true, false);

                cleanupGenerationState(finalGenerationId, ex);
            }

        } catch (RateLimitExceededException e) {
            sendRateLimitMessage(userId, null, e);
        } catch (Exception e) {
            log.warn("处理消息错误: {}", e.getMessage(), e);

            if (generationId != null) {
                chatGenerationStateService.markFailed(generationId, e.getMessage());
                cleanupGenerationState(generationId, e);
            }

            handleError(userId, generationId, e);
        }
    }

    /**
     * 向用户推送限流错误通知（HTTP 429 语义，携带建议等待秒数）
     *
     * @param userId
     *            用户 ID
     * @param generationId
     *            生成任务 ID（限流发生在创建任务前时为 null）
     * @param exception
     *            限流异常
     */
    private void sendRateLimitMessage(String userId, String generationId, RateLimitExceededException exception) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "error");
        payload.put("generationId", generationId);
        payload.put("code", 429);
        payload.put("message", exception.getMessage());
        payload.put("retryAfterSeconds", exception.getRetryAfterSeconds());

        chatSessionRegistry.sendJsonToUser(userId, payload);
    }

    /**
     * ReAct 循环的异常包裹层：循环内任何未捕获异常统一转成任务失败（标记状态、 推送错误与中断通知、清理内存态），保证线程池线程不因未捕获异常留下脏状态
     *
     * @param userId
     *            用户 ID
     * @param userMessage
     *            用户消息原文
     * @param conversationId
     *            会话 ID
     * @param generationId
     *            生成任务 ID
     * @param history
     *            历史对话消息
     * @param responseFuture
     *            响应完成 Future
     */
    private void runReActLoopSafely(String userId, String userMessage, String conversationId, String generationId,
        List<Map<String, String>> history, CompletableFuture<String> responseFuture) {
        try {
            runReActLoop(userId, userMessage, conversationId, generationId, history, responseFuture);
        } catch (Exception e) {
            log.warn("ReAct 循环执行失败: generationId={}", generationId, e);

            chatGenerationStateService.markFailed(generationId, e.getMessage());

            handleError(userId, generationId, e);

            sendCompletionNotification(userId, generationId, conversationId, true, false);

            cleanupGenerationState(generationId, e);
        }
    }

    /**
     * ReAct 决策循环主流程：流式调用模型，模型返回 tool_calls 则执行工具并把结果 以 tool message 回填后进入下一轮；返回纯文本则视为最终回答并收尾。
     * <p>
     * 具体约束：轮次超限后注入"禁止再调用工具"的收尾指令强制模型作答； 工具执行数达到预算后不再真实执行，改为向模型下发提示； 工具内容已流式输出给用户时立即收尾，避免模型重写造成内容重复。
     *
     * @param userId
     *            用户 ID
     * @param userMessage
     *            用户消息原文
     * @param conversationId
     *            会话 ID
     * @param generationId
     *            生成任务 ID
     * @param history
     *            历史对话消息
     * @param responseFuture
     *            响应完成 Future
     */
    private void runReActLoop(String userId, String userMessage, String conversationId, String generationId,
        List<Map<String, String>> history, CompletableFuture<String> responseFuture) {
        List<Map<String, Object>> messages =
            llmProviderRouter.buildReActMessages(userMessage, "", history, buildRecentFeedbackGuidance(userId));
        int executedToolCalls = 0;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;

        for (int round = 1; round <= MAX_REACT_ROUNDS; round++) {
            if (finishCancelledGeneration(generationId, responseFuture, responseBuilders.get(generationId))) {
                return;
            }

            ReActTurn turn =
                streamReActTurnBlocking(userId, conversationId, generationId, messages, agentToolRegistry.getTools());
            if (Objects.isNull(turn)) {
                // 上游 stream 被取消（如用户点 stop），保证内存映射被回收
                cleanupGenerationState(generationId, null);

                return;
            }

            totalPromptTokens += turn.getPromptTokens();
            totalCompletionTokens += turn.getCompletionTokens();

            if (turn.getToolCalls().isEmpty()) {
                finalizeResponse(userId, userMessage, conversationId, generationId, responseFuture,
                    responseBuilders.get(generationId), new StreamCompletion(turn.getFinishReason(), totalPromptTokens,
                        totalCompletionTokens, turn.getContent().length()));

                return;
            }

            messages.add(turn.getAssistantMessage());
            for (ToolCallDecision toolCall : turn.getToolCalls()) {
                ExecutedToolResult executedToolResult;
                if (executedToolCalls >= MAX_REACT_TOOL_CALLS) {
                    executedToolResult = new ExecutedToolResult("工具调用预算已用尽，本次工具未执行。请基于已有 tool 结果给出最终回答。", false);

                    sendToolCallStatus(userId, generationId, conversationId, toolCall, "failed");
                } else {
                    executedToolResult =
                        executeToolForReAct(userId, userMessage, generationId, conversationId, toolCall);

                    executedToolCalls++;
                }
                messages.add(toolMessage(toolCall.getId(), executedToolResult.getContent()));
                if (executedToolResult.isStreamedToUser()) {
                    finalizeResponse(userId, userMessage, conversationId, generationId, responseFuture,
                        responseBuilders.get(generationId),
                        new StreamCompletion("tool_streamed", totalPromptTokens, totalCompletionTokens,
                            responseBuilders.get(generationId) != null ? responseBuilders.get(generationId).length()
                                : 0));
                    return;
                }
            }
        }

        messages.add(Map.of("role", "user", "content", "ReAct 轮次预算已用尽，请不要再调用工具，直接基于已有 tool 结果给出最终回答。"));
        ReActTurn finalTurn = streamReActTurnBlocking(userId, conversationId, generationId, messages, List.of());
        if (finalTurn == null) {
            cleanupGenerationState(generationId, null);
            return;
        }
        totalPromptTokens += finalTurn.getPromptTokens();
        totalCompletionTokens += finalTurn.getCompletionTokens();
        finalizeResponse(userId, userMessage, conversationId, generationId, responseFuture,
            responseBuilders.get(generationId), new StreamCompletion(finalTurn.getFinishReason(), totalPromptTokens,
                totalCompletionTokens, finalTurn.getContent().length()));
    }

    /**
     * 构造回填模型的 tool role 消息，空值统一规整为空串以满足协议格式
     *
     * @param toolCallId
     *            工具调用 ID（对应 assistant 消息中的 tool_calls）
     * @param content
     *            工具执行结果文本
     * @return tool role 消息
     */
    private Map<String, Object> toolMessage(String toolCallId, String content) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", StringUtils.isBlank(toolCallId) ? "" : toolCallId);
        message.put("content", StringUtils.isBlank(content) ? "" : content);

        return message;
    }

    /**
     * 执行单次 ReAct 工具调用并推送执行状态通知
     * <p>
     * generate_summary 比较特殊：其摘要内容会通过 chunkConsumer 直接流式输出给前端， 因此中途失败时不再让模型重写（会拼接出"半截旧摘要+新摘要"）， 而是提示中断并以
     * streamedToUser=true 收尾。search_knowledge 的结果负责刷新引用映射。
     *
     * @param userId
     *            用户 ID
     * @param userMessage
     *            用户消息原文（用于构造引用详情的检索原文）
     * @param generationId
     *            生成任务 ID
     * @param conversationId
     *            会话 ID
     * @param toolCall
     *            模型决策出的工具调用
     * @return 工具执行结果（异常时降级为失败提示文本，不向上抛出）
     */
    private ExecutedToolResult executeToolForReAct(String userId, String userMessage, String generationId,
        String conversationId, ToolCallDecision toolCall) {
        sendToolCallStatus(userId, generationId, conversationId, toolCall, "executing");

        AtomicBoolean summaryStreamStarted = new AtomicBoolean(false);

        try {
            Consumer<String> toolChunkConsumer = "generate_summary".equals(toolCall.getName()) ? chunk -> {
                if (StringUtils.isBlank(chunk)) {
                    return;
                }

                if (summaryStreamStarted.compareAndSet(false, true)) {
                    appendStreamChunk(userId, generationId, conversationId, "\n\n");
                }

                appendStreamChunk(userId, generationId, conversationId, chunk);
            } : null;
            ToolExecutionResult toolResult =
                agentToolRegistry.executeTool(toolCall.getName(), toolCall.getArguments(), userId, toolChunkConsumer);

            // search_knowledge 返回的 SearchResult 列表与模型 prompt 中的 [N] 编号一一对应，
            // 必须把它落到 generationReferenceMappings 里，否则前端点击引用拿不到 MD5/页码。
            if ("search_knowledge".equals(toolCall.getName())) {
                replaceReferencesFromSearchTool(generationId, userMessage, toolResult);
            }

            String content = toolResult.getContent();
            if (StringUtils.isBlank(content)) {
                content = "工具 " + toolCall.getName() + " 执行成功，但没有返回可展示内容。";
            }

            sendToolCallStatus(userId, generationId, conversationId, toolCall, "success");

            return new ExecutedToolResult(content, toolResult.isStreamedToUser());
        } catch (Exception exception) {
            log.warn("ReAct Agent Tool 执行失败，作为 tool message 返回模型: name={}, generationId={}", toolCall.getName(),
                generationId, exception);

            sendToolCallStatus(userId, generationId, conversationId, toolCall, "failed");
            // generate_summary 已经把部分摘要流给前端，再让模型重写会拼出"半个旧摘要 + 新摘要"。
            // 直接以失败提示收尾，让 ReAct 循环立即 finalize，避免数据不一致。
            if ("generate_summary".equals(toolCall.getName()) && summaryStreamStarted.get()) {
                appendStreamChunk(userId, generationId, conversationId,
                    "\n\n（摘要流式生成中断：" + exception.getMessage() + "）");

                return new ExecutedToolResult("工具 " + toolCall.getName() + " 已部分流式输出后失败: " + exception.getMessage(),
                    true);
            }

            return new ExecutedToolResult("工具 " + toolCall.getName() + " 执行失败: " + exception.getMessage(), false);
        }
    }

    /**
     * 用 search_knowledge 的检索结果整体覆盖当前生成任务的引用映射： 按模型 prompt 中的 [1]..[K] 编号顺序重建编号，并同步写入 Redis 供跨进程查询
     *
     * @param generationId
     *            生成任务 ID
     * @param userMessage
     *            用户消息原文
     * @param toolResult
     *            search_knowledge 的执行结果
     */
    private void replaceReferencesFromSearchTool(String generationId, String userMessage,
        ToolExecutionResult toolResult) {
        if (Objects.isNull(toolResult) || Objects.isNull(toolResult.getData())) {
            return;
        }

        Object resultsObj = toolResult.getData().get("results");
        if (!(resultsObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return;
        }

        Map<Integer, ReferenceInfo> mapping = new HashMap<>();
        int referenceNumber = 1;
        for (Object item : rawList) {
            if (!(item instanceof SearchResult result) || StringUtils.isBlank(result.getFileMd5())) {
                continue;
            }

            String fileLabel = StringUtils.isNotBlank(result.getFileName()) ? result.getFileName() : "unknown";
            mapping.put(referenceNumber, buildReferenceInfo(result, fileLabel, userMessage));

            referenceNumber++;
        }

        if (mapping.isEmpty()) {
            return;
        }

        // 模型每次 search_knowledge 都会拿到 [1]..[K] 重新编号，因此按"覆盖"语义保存最新一次的引用映射。
        generationReferenceMappings.put(generationId, mapping);

        chatGenerationStateService.updateReferenceMappings(generationId, toSerializableReferenceMappings(mapping));
    }

    /**
     * 将单条检索结果转换为前端可展示的引用详情：截断匹配片段、构造证据摘要并补充召回方式标签
     *
     * @param result
     *            检索结果
     * @param fileLabel
     *            文件名（缺失时回退为 unknown）
     * @param userMessage
     *            用户消息原文
     * @return 引用详情
     */
    private ReferenceInfo buildReferenceInfo(SearchResult result, String fileLabel, String userMessage) {
        String matchedChunkText = trimToMaxLength(StringUtils.isNotBlank(result.getMatchedChunkText())
            ? result.getMatchedChunkText() : result.getTextContent(), MAX_MATCHED_CHUNK_LEN);

        String evidenceSnippet = buildEvidenceSnippet(userMessage, result.getAnchorText(), matchedChunkText);

        return new ReferenceInfo(result.getFileMd5(), fileLabel, result.getPageNumber(), result.getAnchorText(),
            result.getRetrievalMode(), buildRetrievalLabel(result.getRetrievalMode()),
            normalizeEvidenceText(userMessage), matchedChunkText, evidenceSnippet, result.getScore(),
            result.getChunkId());
    }

    /**
     * 将召回方式枚举转成中文展示标签
     *
     * @param retrievalMode
     *            召回方式（TEXT_ONLY 表示纯关键词召回）
     * @return 召回方式标签
     */
    private String buildRetrievalLabel(String retrievalMode) {
        if ("TEXT_ONLY".equalsIgnoreCase(retrievalMode)) {
            return "关键词召回";
        }

        return "混合召回（语义相关 + 关键词命中）";
    }

    /**
     * 构造引用点击时展示的证据摘要：优先取锚点文本； 其次在匹配片段中挑出首个不短于 12 字符的完整句子； 都缺失时回退用用户问题兜底，最后统一按上限截断
     *
     * @param userMessage
     *            用户消息原文
     * @param anchorText
     *            检索命中的锚点文本
     * @param matchedChunkText
     *            匹配片段文本
     * @return 证据摘要（已规整空白并截断）
     */
    private String buildEvidenceSnippet(String userMessage, String anchorText, String matchedChunkText) {
        String normalizedAnchorText = normalizeEvidenceText(anchorText);
        if (StringUtils.isNotBlank(normalizedAnchorText)) {
            return trimToMaxLength(normalizedAnchorText, MAX_EVIDENCE_SNIPPET_LEN);
        }

        String normalizedMatchedChunk = normalizeEvidenceText(matchedChunkText);
        if (StringUtils.isBlank(normalizedMatchedChunk)) {
            String normalizedUserMessage = normalizeEvidenceText(userMessage);
            if (StringUtils.isNotBlank(normalizedUserMessage)) {
                return trimToMaxLength(normalizedUserMessage, MAX_EVIDENCE_SNIPPET_LEN);
            }

            return "";
        }

        String[] sentences = normalizedMatchedChunk.split("(?<=[。！？!?；;])");
        for (String sentence : sentences) {
            String trimmedSentence = sentence.trim();
            if (trimmedSentence.length() >= 12) {
                return trimToMaxLength(trimmedSentence, MAX_EVIDENCE_SNIPPET_LEN);
            }
        }

        return trimToMaxLength(normalizedMatchedChunk, MAX_EVIDENCE_SNIPPET_LEN);
    }

    /**
     * 规整文本并按最大长度截断，超长时以省略号结尾
     *
     * @param value
     *            原文本
     * @param maxLength
     *            最大保留长度（字符）
     * @return 规整后的文本
     */
    private String trimToMaxLength(String value, int maxLength) {
        String normalized = normalizeEvidenceText(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }

        return normalized.substring(0, maxLength) + "…";
    }

    /**
     * 规整证据类文本：折叠连续空白为单个空格并去除首尾空白
     *
     * @param value
     *            原文本
     * @return 规整后的文本（null/空白返回空串）
     */
    private String normalizeEvidenceText(String value) {
        return StringUtils.isBlank(value) ? "" : value.replaceAll("\\s+", " ").trim();
    }

    /**
     * 向用户推送工具执行状态通知（前端用于渲染"正在调用工具/成功/失败"的进度提示）
     *
     * @param userId
     *            用户 ID
     * @param generationId
     *            生成任务 ID
     * @param conversationId
     *            会话 ID
     * @param toolCall
     *            工具调用
     * @param status
     *            状态（executing/success/failed）
     */
    private void sendToolCallStatus(String userId, String generationId, String conversationId,
        ToolCallDecision toolCall, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "tool_call");
        payload.put("tool", toolCall.getName());
        payload.put("toolCallId", toolCall.getId());
        payload.put("status", status);
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("timestamp", System.currentTimeMillis());

        chatSessionRegistry.sendJsonToUser(userId, payload);
    }

    /**
     * 收尾一次生成任务：校验构建器与响应内容非空后完成响应 Future， 事务性地把问答落 MySQL 并写入 Redis 会话历史， 标记任务完成、推送 completion 通知（含引用映射与降级提示），最后清理内存态。
     * <p>
     * 收尾路径是幂等的：任务已被取消、响应已完成或构建器缺失时直接返回，避免重复落库。
     *
     * @param userId
     *            用户 ID
     * @param userMessage
     *            用户消息原文
     * @param conversationId
     *            会话 ID
     * @param generationId
     *            生成任务 ID
     * @param responseFuture
     *            响应完成 Future
     * @param responseBuilder
     *            响应内容构建器（可能已被取消路径清理）
     * @param completion
     *            本次生成的汇总信息
     */
    private void finalizeResponse(String userId, String userMessage, String conversationId, String generationId,
        CompletableFuture<String> responseFuture, StringBuilder responseBuilder, StreamCompletion completion) {
        if (finishCancelledGeneration(generationId, responseFuture, responseBuilder)) {
            return;
        }

        if (Objects.isNull(responseBuilder)) {
            if (responseFuture.isDone() || !responseFutures.containsKey(generationId)) {
                return;
            }
            RuntimeException exception = new RuntimeException("响应构建器为空");
            if (responseFuture.completeExceptionally(exception)) {
                handleError(userId, generationId, exception);

                sendCompletionNotification(userId, generationId, conversationId, true, false);

                chatGenerationStateService.markFailed(generationId, exception.getMessage());

                cleanupGenerationState(generationId, exception);
            }

            return;
        }

        String completeResponse = responseBuilder.toString();
        if (completeResponse.isBlank()) {
            RuntimeException exception = new RuntimeException("模型未返回有效内容，请稍后重试");
            if (responseFuture.completeExceptionally(exception)) {
                handleError(userId, generationId, exception);

                sendCompletionNotification(userId, generationId, conversationId, true, false);

                chatGenerationStateService.markFailed(generationId, exception.getMessage());

                cleanupGenerationState(generationId, exception);
            }
            return;
        }

        if (!responseFuture.complete(completeResponse)) {
            return;
        }

        Map<Integer, ReferenceInfo> referenceMappings = generationReferenceMappings.get(generationId);
        // 先把消息事务性地落 MySQL；只有 MySQL 成功后才写 Redis 短期会话历史，
        // 否则两个数据源会出现一边有记录、一边没有的不一致状态。
        boolean persisted =
            persistConversation(userId, userMessage, completeResponse, conversationId, referenceMappings);
        if (persisted) {
            updateConversationHistory(conversationId, userMessage, completeResponse, referenceMappings);
        } else {
            log.warn("MySQL 落库失败，跳过 Redis 会话历史写入以保持两端一致: generationId={}, conversationId={}", generationId,
                conversationId);
        }

        chatGenerationStateService.markCompleted(generationId, toSerializableReferenceMappings(referenceMappings));

        sendCompletionNotification(userId, generationId, conversationId, false, !persisted);

        cleanupGenerationState(generationId, null);
    }

    /**
     * 把本轮问答追加进 Redis 会话历史（短期上下文，保存 7 天）： 写入用户与助手消息（带时间戳和引用映射），超出 20 条时仅保留最近 20 条
     *
     * @param conversationId
     *            会话 ID
     * @param userMessage
     *            用户消息原文
     * @param response
     *            助手完整回答
     * @param referenceMapping
     *            引用编号映射（可为空）
     */
    private void updateConversationHistory(String conversationId, String userMessage, String response,
        Map<Integer, ReferenceInfo> referenceMapping) {
        String key = "conversation:" + conversationId;
        List<Map<String, Object>> history = getConversationHistoryRecords(conversationId);

        // 获取当前时间戳
        String currentTimestamp =
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

        // 添加用户消息（带时间戳）
        Map<String, Object> userMsgMap = new HashMap<>();
        userMsgMap.put("role", "user");
        userMsgMap.put("content", userMessage);
        userMsgMap.put("timestamp", currentTimestamp);
        history.add(userMsgMap);

        // 添加助手回复（带时间戳）
        Map<String, Object> assistantMsgMap = new HashMap<>();
        assistantMsgMap.put("role", "assistant");
        assistantMsgMap.put("content", response);
        assistantMsgMap.put("timestamp", currentTimestamp);
        if (!CollectionUtils.isEmpty(referenceMapping)) {
            assistantMsgMap.put("referenceMappings", toSerializableReferenceMappings(referenceMapping));
        }
        history.add(assistantMsgMap);

        // 限制历史记录长度，保留最近的20条消息
        if (history.size() > 20) {
            history = history.subList(history.size() - 20, history.size());
        }

        try {
            String json = objectMapper.writeValueAsString(history);
            redisTemplate.opsForValue().set(key, json, Duration.ofDays(7));
        } catch (JsonProcessingException e) {
            log.error("序列化对话历史出错: {}, 会话ID: {}", e.getMessage(), conversationId, e);
        }
    }

    /**
     * 将问答记录持久化到 MySQL（消息表 + 会话标题/更新时间），失败时只记日志不抛出， 由调用方依据返回值决定是否继续写 Redis 历史
     *
     * @param userId
     *            用户 ID
     * @param userMessage
     *            用户消息原文
     * @param completeResponse
     *            助手完整回答
     * @param conversationId
     *            会话 ID
     * @param referenceMappings
     *            引用编号映射（可为空）
     * @return true 表示落库成功
     */
    private boolean persistConversation(String userId, String userMessage, String completeResponse,
        String conversationId, Map<Integer, ReferenceInfo> referenceMappings) {
        try {
            Long userIdLong = Long.parseLong(userId);

            conversationService.recordConversation(userIdLong, userMessage, completeResponse, conversationId,
                toSerializableReferenceMappings(referenceMappings));

            return true;
        } catch (Exception e) {
            log.error("持久化对话历史失败: userId={}, conversationId={}", userId, conversationId, e);

            return false;
        }
    }

    /**
     * 向用户推送生成完成通知：携带状态、引用映射； 持久化降级（MySQL 落库失败）时附带 persistenceDegraded 标记与提示文案
     *
     * @param userId
     *            用户 ID
     * @param generationId
     *            生成任务 ID
     * @param conversationId
     *            会话 ID
     * @param failed
     *            是否以失败收尾
     * @param persistenceDegraded
     *            是否发生持久化降级
     */
    private void sendCompletionNotification(String userId, String generationId, String conversationId, boolean failed,
        boolean persistenceDegraded) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", "completion");
        notification.put("generationId", generationId);
        notification.put("conversationId", conversationId);
        notification.put("status", failed ? "failed" : "finished");
        notification.put("message", failed ? "响应已中断" : "响应已完成");
        notification.put("timestamp", System.currentTimeMillis());
        notification.put("date", java.time.LocalDateTime.now().toString());

        if (!failed) {
            Map<Integer, ReferenceInfo> referenceMappings = generationReferenceMappings.get(generationId);
            if (!CollectionUtils.isEmpty(referenceMappings)) {
                notification.put("referenceMappings", toSerializableReferenceMappings(referenceMappings));
            }
        }

        if (persistenceDegraded) {
            notification.put("persistenceDegraded", true);
            notification.put("persistenceWarning", "本次回复未能持久化到数据库，刷新后可能无法在历史中找到。");
        }

        chatSessionRegistry.sendJsonToUser(userId, notification);
    }

    /**
     * 将内存中的引用映射（编号 -> ReferenceInfo）转为可序列化的结构（编号字符串 -> 字段 Map）， 供 Redis 存储与 WebSocket 下发使用；入参为空时返回空 Map
     *
     * @param referenceMapping
     *            引用编号映射
     * @return 序列化后的引用映射
     */
    private Map<String, Map<String, Object>>
        toSerializableReferenceMappings(Map<Integer, ReferenceInfo> referenceMapping) {
        Map<String, Map<String, Object>> serialized = new HashMap<>();
        if (CollectionUtils.isEmpty(referenceMapping)) {
            return serialized;
        }

        for (Map.Entry<Integer, ReferenceInfo> entry : referenceMapping.entrySet()) {
            ReferenceInfo detail = entry.getValue();
            Map<String, Object> item = new HashMap<>();
            item.put("fileMd5", detail.getFileMd5());
            item.put("fileName", detail.getFileName());
            item.put("pageNumber", detail.getPageNumber());
            item.put("anchorText", detail.getAnchorText());
            item.put("retrievalMode", detail.getRetrievalMode());
            item.put("retrievalLabel", detail.getRetrievalLabel());
            item.put("retrievalQuery", detail.getRetrievalQuery());
            item.put("matchedChunkText", detail.getMatchedChunkText());
            item.put("evidenceSnippet", detail.getEvidenceSnippet());
            item.put("score", detail.getScore());
            item.put("chunkId", detail.getChunkId());
            serialized.put(String.valueOf(entry.getKey()), item);
        }

        return serialized;
    }

    /**
     * 向用户推送通用 AI 服务错误通知，错误细节只记录日志不下发给前端
     *
     * @param userId
     *            用户 ID
     * @param generationId
     *            生成任务 ID（可为 null）
     * @param error
     *            原始错误
     */
    private void handleError(String userId, String generationId, Throwable error) {
        log.warn("AI服务错误: {}", error.getMessage(), error);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("type", "error");
        errorResponse.put("generationId", generationId);
        errorResponse.put("error", "AI服务暂时不可用，请稍后重试");

        chatSessionRegistry.sendJsonToUser(userId, errorResponse);
    }

    /**
     * 发起一轮 ReAct 模型调用并阻塞等待回合结果
     * <p>
     * 等待期间以短轮询方式检查取消标志，保证用户点停止后能及时中断； 整体受 {@link #GENERATION_COMPLETION_TIMEOUT_SECONDS} 硬超时约束。 回合结果就绪后 Future 回调会把
     * turn 落给本方法。
     *
     * @param userId
     *            用户 ID
     * @param conversationId
     *            会话 ID
     * @param generationId
     *            生成任务 ID
     * @param messages
     *            传给模型的对话消息（system/history/tool 结果等）
     * @param tools
     *            本轮允许模型调用的工具列表
     * @return 回合结果；调用中途被用户取消时返回 null
     */
    private ReActTurn streamReActTurnBlocking(String userId, String conversationId, String generationId,
        List<Map<String, Object>> messages, List<AgentTool> tools) {
        CompletableFuture<ReActTurn> turnFuture = new CompletableFuture<>();
        StreamHandle streamHandle = llmProviderRouter.streamReActTurn(userId, messages, tools,
            REACT_MAX_COMPLETION_TOKENS, chunk -> appendStreamChunk(userId, generationId, conversationId, chunk),
            turnFuture::completeExceptionally, turnFuture::complete);
        activeStreams.put(generationId, streamHandle);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENERATION_COMPLETION_TIMEOUT_SECONDS);
        try {
            while (true) {
                if (isGenerationCancelled(generationId)) {
                    streamHandle.cancel();
                    return null;
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    streamHandle.cancel();
                    throw new RuntimeException("模型响应超时，请稍后重试");
                }
                try {
                    long waitMillis = Math.min(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 200L);
                    return turnFuture.get(Math.max(waitMillis, 1L), TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // 短轮询用于及时响应用户停止生成。
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            streamHandle.cancel();

            throw new RuntimeException("模型响应被中断", exception);
        } catch (ExecutionException exception) {
            streamHandle.cancel();

            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("ReAct 流式模型回合调用失败", cause);
        } finally {
            activeStreams.remove(generationId, streamHandle);
        }
    }

    /**
     * 追加一个流式输出块：写入内存构建器、同步到 Redis 生成快照、并推送给前端； 取消后或内容为空时直接忽略
     *
     * @param userId
     *            用户 ID
     * @param generationId
     *            生成任务 ID
     * @param conversationId
     *            会话 ID
     * @param chunk
     *            文本块
     */
    private void appendStreamChunk(String userId, String generationId, String conversationId, String chunk) {
        if (StringUtils.isBlank(chunk) || isGenerationCancelled(generationId)) {
            return;
        }

        StringBuilder responseBuilder = responseBuilders.get(generationId);
        if (Objects.nonNull(responseBuilder)) {
            responseBuilder.append(chunk);
        }

        chatGenerationStateService.appendChunk(generationId, chunk);

        sendResponseChunk(userId, generationId, conversationId, chunk);
    }

    /**
     * 向用户推送 chunk 类型消息（一段增量回答文本）；检测到停止标志时丢弃后续块
     *
     * @param userId
     *            用户 ID
     * @param generationId
     *            生成任务 ID
     * @param conversationId
     *            会话 ID
     * @param chunk
     *            文本块
     */
    private void sendResponseChunk(String userId, String generationId, String conversationId, String chunk) {
        if (Boolean.TRUE.equals(stopFlags.get(generationId))) {
            log.warn("检测到停止标志，跳过发送响应块: generationId={}", generationId);

            return;
        }

        chatSessionRegistry.sendJsonToUser(userId,
            Map.of("type", "chunk", "generationId", generationId, "conversationId", conversationId, "chunk", chunk));
    }

    /**
     * 检查生成任务是否已被取消，是则完成 Future 并清理内存态
     *
     * @param generationId
     *            生成任务 ID
     * @param responseFuture
     *            响应完成 Future
     * @param responseBuilder
     *            响应内容构建器
     * @return true 表示任务已取消且本方法完成了收尾，调用方应直接返回
     */
    private boolean finishCancelledGeneration(String generationId, CompletableFuture<String> responseFuture,
        StringBuilder responseBuilder) {
        if (!isGenerationCancelled(generationId)) {
            return false;
        }

        if (!responseFuture.isDone()) {
            responseFuture.complete(Objects.nonNull(responseBuilder) ? responseBuilder.toString() : "");
        }

        cleanupGenerationState(generationId, null);

        return true;
    }

    /**
     * 清理单个生成任务的全部内存态（构建器、引用映射、停止标志、活跃流、Future）， Future 未完成且携带异常时异常完成它以释放等待方
     *
     * @param generationId
     *            生成任务 ID
     * @param throwable
     *            需要传递给等待方的异常（正常清理传 null）
     */
    private void cleanupGenerationState(String generationId, Throwable throwable) {
        responseBuilders.remove(generationId);
        generationReferenceMappings.remove(generationId);
        stopFlags.remove(generationId);
        activeStreams.remove(generationId);
        cancelledGenerations.remove(generationId);
        CompletableFuture<String> future = responseFutures.remove(generationId);

        if (Objects.nonNull(throwable) && Objects.nonNull(future) && !future.isDone()) {
            future.completeExceptionally(throwable);
        }
    }

    /**
     * 判断生成任务是否已被用户停止（取消集合或停止标志任一命中即视为取消）
     *
     * @param generationId
     *            生成任务 ID
     * @return true 表示已取消
     */
    private boolean isGenerationCancelled(String generationId) {
        return StringUtils.isNotBlank(generationId)
            && (cancelledGenerations.contains(generationId) || Boolean.TRUE.equals(stopFlags.get(generationId)));
    }

    /**
     * 读取用户最近的显式反馈（点赞/点踩记录）并拼装成注入 system prompt 的偏好指引， 最多取最近 5 条、按时间倒序；无反馈或读取失败时返回空串
     *
     * @param userId
     *            用户 ID
     * @return 反馈指引文本，可为空串
     */
    private String buildRecentFeedbackGuidance(String userId) {
        if (StringUtils.isBlank(userId)) {
            return "";
        }

        try {
            Map<Object, Object> feedbackEntries = redisTemplate.opsForHash().entries("feedback:" + userId);
            if (CollectionUtils.isEmpty(feedbackEntries)) {
                return "";
            }

            StringBuilder guidance = new StringBuilder("近期用户对回答的显式反馈如下，按时间倒序排列，越靠前越新。")
                .append("后续回答需要参考这些偏好：good 表示用户认可这类回答方式，bad 表示需要避免类似问题；").append("如果同一轮回答既有 good 又有 bad，以最新一条为准。\n");
            feedbackEntries.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> -parseFeedbackTimestamp(entry.getKey()))).limit(5)
                .forEach(entry -> guidance.append("- ").append("feedbackTime=").append(entry.getKey()).append("; ")
                    .append(entry.getValue()).append("\n"));

            return guidance.toString().trim();
        } catch (Exception exception) {
            log.warn("读取用户反馈上下文失败: userId={}", userId, exception);

            return "";
        }
    }

    /**
     * 解析反馈记录键中的时间戳（毫秒），非数字或缺省时返回 0
     *
     * @param value
     *            反馈记录键
     * @return 时间戳（毫秒）
     */
    private long parseFeedbackTimestamp(Object value) {
        if (Objects.isNull(value)) {
            return 0L;
        }

        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    /**
     * 读取会话历史并规整为仅含 role/content/timestamp 的消息列表， 供 ReAct 消息构建使用
     *
     * @param conversationId
     *            会话 ID
     * @return 历史消息列表（会话为空时返回空列表）
     */
    private List<Map<String, String>> getConversationHistory(String conversationId) {
        List<Map<String, Object>> records = getConversationHistoryRecords(conversationId);
        List<Map<String, String>> history = new ArrayList<>();
        for (Map<String, Object> message : records) {
            Map<String, String> normalized = new HashMap<>();
            normalized.put("role", String.valueOf(message.getOrDefault("role", "")));
            normalized.put("content", String.valueOf(message.getOrDefault("content", "")));
            Object timestamp = message.get("timestamp");
            if (Objects.nonNull(timestamp)) {
                normalized.put("timestamp", String.valueOf(timestamp));
            }
            history.add(normalized);
        }

        return history;
    }

    /**
     * 从 Redis 读取会话历史的原始记录（含引用映射等附加字段）， JSON 解析失败时返回空列表
     *
     * @param conversationId
     *            会话 ID
     * @return 历史记录列表
     */
    private List<Map<String, Object>> getConversationHistoryRecords(String conversationId) {
        String key = "conversation:" + conversationId;
        String json = redisTemplate.opsForValue().get(key);
        try {
            if (StringUtils.isBlank(json)) {
                return new ArrayList<>();
            }

            List<Map<String, Object>> history =
                objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});

            return history;
        } catch (JsonProcessingException e) {
            log.warn("解析对话历史出错: {}, 会话ID: {}", e.getMessage(), conversationId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 向用户推送 start 类型消息：告知前端本次生成任务与会话的 ID，后续 chunk/completion 事件都以此关联
     *
     * @param userId
     *            用户 ID
     * @param generationId
     *            生成任务 ID
     * @param conversationId
     *            会话 ID
     */
    private void sendGenerationStart(String userId, String generationId, String conversationId) {
        chatSessionRegistry.sendJsonToUser(userId, Map.of("type", "start", "generationId", generationId,
            "conversationId", conversationId, "timestamp", System.currentTimeMillis()));
    }

    /**
     * 获取用户当前会话 ID：Redis 中不存在时新建一个（7 天有效），同一用户连续消息落在同一会话内
     *
     * @param userId
     *            用户 ID
     * @return 会话 ID
     */
    private String getOrCreateConversationId(String userId) {
        String key = "user:" + userId + ":current_conversation";
        String conversationId = redisTemplate.opsForValue().get(key);

        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
            redisTemplate.opsForValue().set(key, conversationId, Duration.ofDays(7));
        } else {
            log.info("获取到用户 {} 的现有会话ID: {}", userId, conversationId);
        }

        return conversationId;
    }
}
