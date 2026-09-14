package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 15:52
 * @Desc: Token 预算限制
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TokenBudgetLimit {

    /**
     * 每分钟最大使用量
     */
    private long minuteMax;

    /**
     * 每分钟窗口大小（秒）
     */
    private long minuteWindowSeconds;

    /**
     * 每日最大使用量
     */
    private long dayMax;

    /**
     * 每日窗口大小（秒）
     */
    private long dayWindowSeconds;
}
