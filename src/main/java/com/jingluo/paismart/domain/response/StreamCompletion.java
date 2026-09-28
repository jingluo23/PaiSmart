package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/28 17:16
 * @Desc: 一次生成任务收尾时的汇总信息：结束原因、累计 token 用量与最终回答长度
 */
@AllArgsConstructor
@Data
public class StreamCompletion {

    /**
     * 结束原因（stop/tool_streamed/length 等，其中 tool_streamed 表示回答由工具直接流出）
     */
    private String finishReason;

    /**
     * 整个 ReAct 循环（含最终收尾回合）累计的 prompt token 用量
     */
    private int promptTokens;

    /**
     * 整个 ReAct 循环累计的 completion token 用量
     */
    private int completionTokens;

    /**
     * 最终回答的字符数
     */
    private int responseChars;
}
