package com.jingluo.paismart.domain.response;

import com.jingluo.paismart.enums.GenerationStatus;
import lombok.Data;

/**
 * 生成任务元数据（不含回答内容），以 JSON 形式存储于 Redis
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 16:47
 */
@Data
public class GenerationMeta {

    /**
     * 生成任务唯一 ID
     */
    private String generationId;

    /**
     * 发起任务的用户 ID
     */
    private String userId;

    /**
     * 所属会话 ID
     */
    private String conversationId;

    /**
     * 用户提问内容
     */
    private String question;

    /**
     * 任务状态：流式中/已完成/已失败/已取消
     */
    private GenerationStatus status;

    /**
     * 创建时间
     */
    private String createdAt;

    /**
     * 最近更新时间
     */
    private String updatedAt;

    /**
     * 失败原因，仅失败时存在
     */
    private String errorMessage;
}
