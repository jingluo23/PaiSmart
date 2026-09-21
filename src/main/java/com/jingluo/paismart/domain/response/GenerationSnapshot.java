package com.jingluo.paismart.domain.response;

import java.util.Map;

import com.jingluo.paismart.enums.GenerationStatus;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 生成任务完整快照（元数据 + 回答内容 + 引用映射）
 * <p>
 * 由 Redis 中分散存储的 meta、content、refs 三部分聚合而成，用于对外查询。
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 16:43
 */
@AllArgsConstructor
@Data
public class GenerationSnapshot {

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
     * 聚合后的完整回答内容
     */
    private String content;

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

    /**
     * 引用映射：引用标记 -> 来源信息（文件名、fileMd5、chunkId 等）
     */
    private Map<String, Map<String, Object>> referenceMappings;
}
