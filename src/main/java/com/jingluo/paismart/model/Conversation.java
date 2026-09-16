package com.jingluo.paismart.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 10:00
 * @Desc: 对话记录实体，存储用户提问与系统回答，支持按用户、时间和逻辑会话检索
 */
@Data
@Entity
@Table(name = "conversations",
    indexes = {@Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_timestamp", columnList = "timestamp"),
        @Index(name = "idx_conversation_id", columnList = "conversation_id")})
public class Conversation {

    /**
     * 对话记录唯一标识
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联用户
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 用户提问内容
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    /**
     * 系统回答内容
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    /**
     * 逻辑会话ID，用于历史记录关联
     */
    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    /**
     * 助手回复对应的引用映射
     */
    @Column(name = "reference_mappings_json", columnDefinition = "LONGTEXT")
    private String referenceMappingsJson;

    /**
     * 对话时间戳
     */
    @CreationTimestamp
    private LocalDateTime timestamp;
}
