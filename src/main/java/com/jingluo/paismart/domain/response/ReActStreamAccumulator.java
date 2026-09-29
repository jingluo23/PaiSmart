package com.jingluo.paismart.domain.response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

/**
 * @Author: 鲸落
 * @Date: 2026/9/28 16:10
 * @Desc: ReAct 单轮流式响应的累积器：聚合 SSE 数据块中的正文、思考内容与 tool_call 片段， 回合结束后还原成 OpenAI 消息结构并结算 token 用量
 */
@Data
public class ReActStreamAccumulator {

    /**
     * 本次流式调用前预留的 token 预算，供结算/回滚使用
     */
    private TokenReservationBundle reservation;

    /**
     * 发起调用前按消息和工具定义估算的 prompt token 数，作为上游未上报 usage 时的兜底
     */
    private int estimatedPromptTokens;

    /**
     * 可见正文片段累积
     */
    private StringBuilder content = new StringBuilder();

    /**
     * 推理模型思考内容片段累积（仅回填对话，不推给用户）
     */
    private StringBuilder reasoningContent = new StringBuilder();

    /**
     * 按 index 聚合的流式 tool_call，键为 OpenAI 增量协议中的 index 字段
     */
    private Map<Integer, StreamingToolCall> toolCalls = new LinkedHashMap<>();

    /**
     * 上游 usage 上报的 prompt token 数，未上报时为 0
     */
    private int promptTokens;

    /**
     * 上游 usage 上报的 completion token 数，未上报时为 0
     */
    private int completionTokens;

    /**
     * 本轮结束原因（stop/tool_calls/length 等）
     */
    private String finishReason;

    /**
     * 结算标记：usage 只允许结算一次；正常完成回调与取消/超时路径可能并发结算，需 CAS 抢占
     */
    @Getter(AccessLevel.NONE)
    private final AtomicBoolean settled = new AtomicBoolean(false);

    /**
     * CAS 抢占结算权：返回 true 表示本次调用获得结算资格，false 表示已被并发路径结算
     */
    public boolean markSettled() {
        return settled.compareAndSet(false, true);
    }

    /**
     * 是否已完成结算
     */
    public boolean isSettled() {
        return settled.get();
    }

    /**
     * 上游未上报 completion 用量时的兜底值，与 ReAct 回合的最大生成长度保持一致
     */
    private static final int DEFAULT_REACT_MAX_COMPLETION_TOKENS = 2000;

    public ReActStreamAccumulator(TokenReservationBundle reservation, int estimatedPromptTokens) {
        this.reservation = reservation;
        this.estimatedPromptTokens = estimatedPromptTokens;
    }

    /**
     * 消费一个流式 tool_call 增量数据块：id/type 为整段直接覆盖， function.name/arguments 为片段逐个追加
     *
     * @param delta
     *            OpenAI 流式协议中 tool_calls 数组内的单个增量节点
     */
    public void appendToolCallDelta(JsonNode delta) {
        int index = delta.path("index").asInt(toolCalls.size());
        StreamingToolCall toolCall = toolCalls.computeIfAbsent(index, ignored -> new StreamingToolCall());
        String id = delta.path("id").asText("");
        if (StringUtils.isNotBlank(id)) {
            toolCall.setId(id);
        }

        String type = delta.path("type").asText("");
        if (StringUtils.isNotBlank(type)) {
            toolCall.setType(type);
        }

        JsonNode function = delta.path("function");
        if (function.isObject()) {
            String name = function.path("name").asText("");
            if (StringUtils.isNotBlank(name)) {
                toolCall.getName().append(name);
            }

            String arguments = function.path("arguments").asText("");
            if (StringUtils.isNotBlank(arguments)) {
                toolCall.getArguments().append(arguments);
            }
        }
    }

    /**
     * 将累积内容还原成可回填对话 messages 的 assistant 消息： 存在 tool_calls 时 content 允许为 null，思考内容以 reasoning_content 附带
     *
     * @return OpenAI 消息结构的 Map
     */
    public Map<String, Object> assistantMessage() {
        Map<String, Object> message = new LinkedHashMap<>();
        List<Map<String, Object>> serializedToolCalls = serializedToolCalls();
        message.put("role", "assistant");

        if (CollectionUtils.isNotEmpty(serializedToolCalls)) {
            String assistantContent = content.toString();
            message.put("content", StringUtils.isBlank(assistantContent) ? null : assistantContent);
            message.put("tool_calls", serializedToolCalls);
        } else {
            message.put("content", content.toString());
        }

        if (!reasoningContent.isEmpty()) {
            message.put("reasoning_content", reasoningContent.toString());
        }

        return message;
    }

    /**
     * 序列化累积的 tool_call：跳过无名称的空片段，缺省 id/type 时补默认值， arguments 缺省为空 JSON
     *
     * @return OpenAI tool_calls 结构列表
     */
    private List<Map<String, Object>> serializedToolCalls() {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (Map.Entry<Integer, StreamingToolCall> entry : toolCalls.entrySet()) {
            StreamingToolCall toolCall = entry.getValue();
            if (toolCall.getName().isEmpty()) {
                continue;
            }

            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", toolCall.getName().toString());
            function.put("arguments", toolCall.getArguments().isEmpty() ? "{}" : toolCall.getArguments().toString());

            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", StringUtils.isBlank(toolCall.getId()) ? "call_" + entry.getKey() : toolCall.getId());
            call.put("type", StringUtils.isBlank(toolCall.getType()) ? "function" : toolCall.getType());
            call.put("function", function);
            serialized.add(call);
        }

        return serialized;
    }

    /**
     * 将累积结果整理成 ReAct 回合：tool_calls 的 arguments 从 JSON 文本解析为参数 Map， 解析失败时降级为空 Map 而不中断整轮回合
     *
     * @return 单轮 ReAct 回合结果
     */
    public ReActTurn toTurn() {
        Map<String, Object> assistantMessage = assistantMessage();
        List<ToolCallDecision> decisions = new ArrayList<>();
        for (Map<String, Object> item : serializedToolCalls()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>)item.get("function");
            String argumentsJson = String.valueOf(function.getOrDefault("arguments", "{}"));
            Map<String, Object> arguments;
            try {
                arguments = new ObjectMapper().readValue(
                    argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson,
                    new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {
                arguments = Map.of();
            }

            decisions.add(new ToolCallDecision(String.valueOf(item.getOrDefault("id", "")),
                String.valueOf(function.getOrDefault("name", "")), arguments));
        }

        return new ReActTurn(content.toString().trim(), decisions, assistantMessage,
            StringUtils.isBlank(finishReason) ? "unknown" : finishReason,
            promptTokens > 0 ? promptTokens : estimatedPromptTokens,
            completionTokens > 0 ? completionTokens : DEFAULT_REACT_MAX_COMPLETION_TOKENS);
    }
}
