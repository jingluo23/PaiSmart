package com.jingluo.paismart.handler;

import java.util.Map;
import java.util.function.Consumer;

import com.jingluo.paismart.domain.response.ToolExecutionResult;

/**
 * Agent 工具处理器接口
 * <p>
 * 每个 Agent 工具（检索、摘要、反馈、统计等）实现此接口并注册到 AgentToolRegistry。
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 17:12
 */
public interface ToolHandler {

    /**
     * 执行工具
     *
     * @param arguments 大模型解析出的工具入参
     * @param userId    发起调用的用户 ID，用于权限过滤与配额结算
     * @param onChunk   流式回调，工具产生的增量文本通过它推送给用户，可为 null
     * @return 工具执行结果
     */
    ToolExecutionResult execute(Map<String, Object> arguments, String userId, Consumer<String> onChunk);
}
