package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/28 17:30
 * @Desc: ReAct 循环中一次工具执行的对内结果：content 回填给模型继续推理， streamedToUser 标记内容是否已流式推送给前端
 */
@AllArgsConstructor
@Data
public class ExecutedToolResult {

    /**
     * 回填给模型的 tool message 内容
     */
    private String content;

    /**
     * 内容是否已经流式输出给前端；为 true 时 ReAct 循环应立即收尾，避免模型重写导致内容重复
     */
    private boolean streamedToUser;
}
