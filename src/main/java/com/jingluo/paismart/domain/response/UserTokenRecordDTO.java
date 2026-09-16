package com.jingluo.paismart.domain.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 17:42
 * @Desc: 用户 Token 变动记录 DTO，用于对外展示 Token 额度的增减流水
 */
@Data
@Builder
public class UserTokenRecordDTO {

    /**
     * 记录 ID
     */
    private Long id;

    /**
     * 记录日期
     */
    private LocalDate recordDate;

    /**
     * Token 类型：LLM 或 EMBEDDING
     */
    private String tokenType;

    /**
     * 变动类型：INCREASE（增加）或 CONSUME（消耗）
     */
    private String changeType;

    /**
     * 变动数量
     */
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
    private String reason;

    /**
     * 备注信息
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 请求次数
     */
    private Long requestCount;
}
