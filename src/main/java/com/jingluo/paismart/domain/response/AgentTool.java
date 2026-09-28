package com.jingluo.paismart.domain.response;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/28 15:52
 * @Desc: ReAct Agent 对外暴露的工具定义，最终会被组装成 OpenAI 兼容的 tools 参数下发给大模型
 */
@AllArgsConstructor
@Data
public class AgentTool {

    /**
     * 工具唯一名称，模型通过该名称发起 tool_calls
     */
    private String name;

    /**
     * 工具用途描述，引导模型判断何时应该调用该工具
     */
    private String description;

    /**
     * 工具入参的 JSON Schema（type/properties/required 结构）
     */
    private Map<String, Object> parameters;
}
