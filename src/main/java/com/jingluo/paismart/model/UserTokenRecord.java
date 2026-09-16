package com.jingluo.paismart.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.jingluo.paismart.enums.ChangeType;
import com.jingluo.paismart.enums.TokenType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 17:58
 * @Desc: 用户 Token 变动流水记录实体，按天记录各类 Token 的增减明细
 */
@Data
@Entity
@Table(name = "user_token_record", indexes = {@Index(name = "idx_user_date", columnList = "userId, recordDate")})
public class UserTokenRecord {

    /**
     * 主键 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 用户 ID
     */
    @Column(nullable = false)
    private String userId;

    /**
     * 记录日期（按天统计）
     */
    @Column(nullable = false)
    private LocalDate recordDate;

    /**
     * Token 类型：LLM 或 EMBEDDING
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TokenType tokenType;

    /**
     * 变动类型：INCREASE（增加）或 CONSUME（消耗）
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ChangeType changeType;

    /**
     * 变动数量
     */
    @Column(nullable = false)
    private Long amount;

    /**
     * 变动前的余额
     */
    private Long balanceBefore;

    /**
     * 变动后的余额
     */
    private Long balanceAfter;

    /**
     * 变动原因描述
     */
    @Column(length = 500)
    private String reason;

    /**
     * 备注信息（如订单号、对话 ID 等）
     */
    @Column(length = 500)
    private String remark;

    /**
     * 请求次数（一次充值或对话可能包含多次 API 请求）
     */
    @Column(nullable = false)
    private Long requestCount = 0L;

    /**
     * 创建时间
     */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * 全参构造函数，用于创建一条 Token 变动流水记录
     *
     * @param userId
     *            用户 ID
     * @param recordDate
     *            记录日期（按天统计）
     * @param tokenType
     *            Token 类型：LLM 或 EMBEDDING
     * @param changeType
     *            变动类型：INCREASE（增加）或 CONSUME（消耗）
     * @param amount
     *            变动数量
     * @param balanceBefore
     *            变动前的余额
     * @param balanceAfter
     *            变动后的余额
     * @param reason
     *            变动原因描述
     * @param remark
     *            备注信息（如订单号、对话 ID 等）
     * @param requestCount
     *            请求次数（一次充值或对话可能包含多次 API 请求）
     */
    public UserTokenRecord(String userId, LocalDate recordDate, TokenType tokenType, ChangeType changeType, Long amount,
        Long balanceBefore, Long balanceAfter, String reason, String remark, long requestCount) {
        this.userId = userId;
        this.recordDate = recordDate;
        this.tokenType = tokenType;
        this.changeType = changeType;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.reason = reason;
        this.remark = remark;
        this.requestCount = requestCount;
    }
}
