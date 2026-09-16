package com.jingluo.paismart.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 充值套餐实体，对应表 recharge_packages
 *
 * @Author: 鲸落
 * @Date: 2026/9/16 11:33
 */
@Data
@Entity
@Table(name = "recharge_packages")
public class RechargePackage {

    /**
     * 套餐 ID（自增主键）
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    /**
     * 套餐名称
     */
    @Column(nullable = false, length = 128, name = "package_name")
    private String packageName;

    /**
     * 套餐价格，单位分
     */
    @Column(nullable = false, name = "package_price")
    private Long packagePrice;

    /**
     * 套餐描述
     */
    @Column(columnDefinition = "TEXT", name = "package_desc")
    private String packageDesc;

    /**
     * 套餐权益
     */
    @Column(columnDefinition = "TEXT", name = "package_benefit")
    private String packageBenefit;

    /**
     * LLM token 数量
     */
    @Column(nullable = false, name = "llm_token")
    private Long llmToken;

    /**
     * Embedding token 数量
     */
    @Column(nullable = false, name = "embedding_token")
    private Long embeddingToken;

    /**
     * 是否启用
     */
    @Column(nullable = false, name = "enabled")
    private Boolean enabled = true;

    /**
     * 是否已删除（逻辑删除）
     */
    @Column(nullable = false, name = "deleted")
    private Boolean deleted = false;

    /**
     * 排序顺序（数字越小越靠前）
     */
    @Column(nullable = false, name = "sort_order")
    private Integer sortOrder = 0;

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
     * 全参构造器（不含 id 及时间戳字段，创建时由数据库/框架自动填充）
     */
    public RechargePackage(String packageName, Long packagePrice, String packageDesc, String packageBenefit,
        Long llmToken, Long embeddingToken, boolean enabled, int sortOrder, boolean deleted) {
        this.packageName = packageName;
        this.packagePrice = packagePrice;
        this.packageDesc = packageDesc;
        this.packageBenefit = packageBenefit;
        this.llmToken = llmToken;
        this.embeddingToken = embeddingToken;
        this.enabled = enabled;
        this.sortOrder = sortOrder;
        this.deleted = deleted;
    }
}
