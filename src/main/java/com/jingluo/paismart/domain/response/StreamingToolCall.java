package com.jingluo.paismart.domain.response;

import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/28 16:12
 * @Desc: 流式 tool_call 的增量累积结构：id/type 为整段下发直接覆盖， name/arguments 以片段追加方式拼接
 */
@Data
public class StreamingToolCall {

    /**
     * 工具调用 ID，用于后续 tool message 的 tool_call_id 关联
     */
    private String id;

    /**
     * 调用类型，OpenAI 兼容协议下固定为 function
     */
    private String type;

    /**
     * 工具名称片段累积
     */
    private StringBuilder name = new StringBuilder();

    /**
     * 工具入参 JSON 文本片段累积
     */
    private StringBuilder arguments = new StringBuilder();
}
