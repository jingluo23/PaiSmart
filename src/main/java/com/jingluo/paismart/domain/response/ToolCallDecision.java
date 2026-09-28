package com.jingluo.paismart.domain.response;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/28 15:49
 * @Desc: 模型在单轮 ReAct 回合中决策出的工具调用：arguments 已从 JSON 文本解析为参数 Map
 */
@AllArgsConstructor
@Data
public class ToolCallDecision {

    /**
     * 工具调用 ID，透传自模型的 tool_calls
     */
    private String id;

    /**
     * 待执行的工具名称
     */
    private String name;

    /**
     * 工具入参（解析失败时为空 Map）
     */
    private Map<String, Object> arguments;
}
