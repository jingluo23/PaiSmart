package com.jingluo.paismart.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * @Date: 2026/9/15 15:38
 * @Desc: 邀请码实体
 */
@Data
@Entity
@Table(name = "invite_codes", indexes = {@Index(name = "idx_invite_code_code", columnList = "code", unique = true),
    @Index(name = "idx_invite_code_enabled", columnList = "enabled")})
public class InviteCode {

    /**
     * 主键 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 邀请码（唯一）
     */
    @Column(nullable = false, unique = true, length = 64)
    private String code;

    /**
     * 最大可用次数
     */
    @Column(name = "max_uses", nullable = false)
    private Integer maxUses;

    /**
     * 已使用次数
     */
    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    /**
     * 过期时间（为空表示永不过期）
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /**
     * 是否启用
     */
    @Column(nullable = false)
    private Boolean enabled = true;

    /**
     * 创建人（管理员）
     */
    @ManyToOne
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    /**
     * 创建时间
     */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * 全参构造函数（不含主键和时间戳，由数据库与框架自动生成）
     *
     * @param code
     *            邀请码（唯一）
     * @param maxUses
     *            最大可用次数
     * @param usedCount
     *            已使用次数
     * @param expiresAt
     *            过期时间（为空表示永不过期）
     * @param enabled
     *            是否启用
     * @param createdBy
     *            创建人（管理员）
     */
    public InviteCode(String code, int maxUses, int usedCount, LocalDateTime expiresAt, Boolean enabled,
        User createdBy) {
        this.code = code;
        this.maxUses = maxUses;
        this.usedCount = usedCount;
        this.expiresAt = expiresAt;
        this.enabled = enabled;
        this.createdBy = createdBy;
    }
}
