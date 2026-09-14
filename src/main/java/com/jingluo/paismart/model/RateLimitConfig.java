package com.jingluo.paismart.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 16:00
 * @Desc: 限制类
 */
@Data
@Entity
@Table(name = "rate_limit_configs")
public class RateLimitConfig {

    /**
     * 配置键
     */
    @Id
    @Column(name = "config_key", nullable = false, length = 64)
    private String configKey;

    /**
     * 单个令牌最大值
     */
    @Column(name = "single_max")
    private Integer singleMax;

    /**
     * 单个令牌窗口秒数
     */
    @Column(name = "single_window_seconds")
    private Long singleWindowSeconds;

    /**
     * 分钟令牌最大值
     */
    @Column(name = "minute_max")
    private Long minuteMax;

    /**
     * 分钟令牌窗口秒数
     */
    @Column(name = "minute_window_seconds")
    private Long minuteWindowSeconds;

    /**
     * 天令牌最大值
     */
    @Column(name = "day_max")
    private Long dayMax;

    /**
     * 天令牌窗口秒数
     */
    @Column(name = "day_window_seconds")
    private Long dayWindowSeconds;

    /**
     * 创建人
     */
    @Column(name = "updated_by", nullable = false, length = 255)
    private String updatedBy;

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
}
