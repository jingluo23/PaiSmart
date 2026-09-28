package com.jingluo.paismart.domain.response;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/28 15:48
 * @Desc: ReAct 循环中一轮完整的模型回合结果：包含本轮文本、决策出的工具调用、 可直接回填 messages 的 assistant 消息以及 token 用量
 */
@AllArgsConstructor
@Data
public class ReActTurn {

    /**
     * 本轮模型输出的可见文本（已去除首尾空白）
     */
    private String content;

    /**
     * 本轮决策出的工具调用列表，为空表示模型已给出最终回答
     */
    private List<ToolCallDecision> toolCalls;

    /**
     * 按 OpenAI 消息结构组装的 assistant 消息（含 tool_calls），可直接追加进对话 messages
     */
    private Map<String, Object> assistantMessage;

    /**
     * 本轮流式响应的结束原因（stop/tool_calls/length 等）
     */
    private String finishReason;

    /**
     * 本轮 prompt token 用量（上游未上报时回退为预估值）
     */
    private int promptTokens;

    /**
     * 本轮 completion token 用量（上游未上报时回退为默认上限值）
     */
    private int completionTokens;
}
