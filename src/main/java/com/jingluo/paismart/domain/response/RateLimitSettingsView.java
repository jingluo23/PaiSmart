package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 15:36
 * @Desc:
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RateLimitSettingsView {

    /**
     * 聊天消息窗口限制
     */
    private WindowLimitView chatMessage;

    /**
     * LLM 请求窗口限制
     */
    private TokenBudgetView llmGlobalToken;

    /**
     * Embedding 上传窗口限制
     */
    private TokenBudgetView embeddingUploadToken;

    /**
     * Embedding 查询窗口限制
     */
    private DualWindowLimitView embeddingQueryRequest;

    /**
     * Embedding 查询窗口限制
     */
    private TokenBudgetView embeddingQueryGlobalToken;
}
