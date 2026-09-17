package com.jingluo.paismart.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.jingluo.paismart.enums.OrderStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 充值订单实体，对应 recharge_orders 表
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 9:46
 */
@Data
@Entity
@Table(name = "recharge_orders")
public class RechargeOrder {

    /**
     * 订单 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 业务单号（外部系统唯一）
     */
    @Column(nullable = false, name = "trade_no", unique = true)
    private String tradeNo;

    /**
     * 用户 ID（关联 users 表）
     */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /**
     * 套餐 ID（如果是自定义充值，则为 null）
     */
    @Column(nullable = false, name = "package_id")
    private Integer packageId;

    /**
     * 订单金额，单位分
     */
    @Column(nullable = false)
    private Long amount;

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
     * 微信交易流水号
     */
    @Column(name = "wx_transaction_id")
    private String wxTransactionId;

    /**
     * 订单状态
     */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    /**
     * 订单描述
     */
    @Column
    private String description;

    /**
     * 支付成功时间
     */
    @Column(name = "pay_time")
    private LocalDateTime payTime;

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
     * 构造充值订单（packageId 传 0 表示自定义充值，wxTransactionId 支付前为空字符串）
     */
    public RechargeOrder(String tradeNo, String userId, int packageId, Long amount, Long llmToken, Long embeddingToken,
        OrderStatus orderStatus, String description, String wxTransactionId) {
        this.tradeNo = tradeNo;
        this.userId = userId;
        this.packageId = packageId;
        this.amount = amount;
        this.llmToken = llmToken;
        this.embeddingToken = embeddingToken;
        this.status = orderStatus;
        this.description = description;
        this.wxTransactionId = wxTransactionId;
    }
}
