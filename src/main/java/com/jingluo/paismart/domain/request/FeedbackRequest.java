package com.jingluo.paismart.domain.request;

import lombok.Data;

/**
 * 用户反馈请求体（submit_feedback）
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 16:57
 */
@Data
public class FeedbackRequest {

    /**
     * 反馈评价，只允许 good 或 bad
     */
    private String rating;

    /**
     * 反馈原因说明，可为空
     */
    private String reason;

    /**
     * 关联的会话 ID，用于反馈溯源，可为空
     */
    private String conversationId;

    /**
     * 关联的生成任务 ID，用于反馈溯源，可为空
     */
    private String generationId;
}
