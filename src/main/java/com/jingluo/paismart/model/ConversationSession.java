package com.jingluo.paismart.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.jingluo.paismart.enums.SessionStatusEnum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * @Date: 2026/9/20 11:04
 * @Desc: 对话会话实体，记录用户创建的每个逻辑会话及其归属用户、标题与状态
 */
@Data
@Entity
@Table(name = "conversation_sessions",
    indexes = {@Index(name = "idx_cs_user_id", columnList = "user_id"),
        @Index(name = "idx_cs_conversation_id", columnList = "conversation_id", unique = true),
        @Index(name = "idx_cs_status", columnList = "status")})
public class ConversationSession {

    /**
     * 主键，数据库自增
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 会话所属用户
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 逻辑会话ID，UUID 格式，全局唯一，用于关联该会话下的消息记录
     */
    @Column(name = "conversation_id", length = 64, nullable = false, unique = true)
    private String conversationId;

    /**
     * 会话标题，用户未重命名时由接口返回默认标题
     */
    @Column(length = 255)
    private String title;

    /**
     * 会话状态，创建时默认为 ACTIVE
     */
    @Column(length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private SessionStatusEnum status = SessionStatusEnum.ACTIVE;

    /**
     * 创建时间，由 Hibernate 自动填充
     */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * 最后更新时间，由 Hibernate 自动填充
     */
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
