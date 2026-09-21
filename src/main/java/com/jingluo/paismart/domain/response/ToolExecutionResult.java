package com.jingluo.paismart.domain.response;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Agent 工具执行结果
 * <p>
 * 同时承载给大模型看的文本描述（content）与给前端展示的结构化数据（data）。
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 17:04
 */
@AllArgsConstructor
@Data
public class ToolExecutionResult {

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 是否执行成功
     */
    private boolean success;

    /**
     * 面向大模型的执行结果文本
     */
    private String content;

    /**
     * 结构化结果数据
     */
    private Map<String, Object> data;

    /**
     * 增量内容是否已通过流式推送给用户（避免重复推送）
     */
    private boolean streamedToUser;

    /**
     * 构造工具执行结果（未流式推送场景，streamedToUser=false）
     *
     * @param toolName 工具名称
     * @param success  是否执行成功
     * @param content  面向大模型的执行结果文本
     * @param data     结构化结果数据
     */
    public ToolExecutionResult(String toolName, boolean success, String content, Map<String, Object> data) {
        this.toolName = toolName;
        this.success = success;
        this.content = content;
        this.data = data;
    }
}
