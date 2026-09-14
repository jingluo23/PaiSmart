package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 14:59
 * @Desc:
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyTokenQuota {

    /**
     * 是否启用
     */
    private boolean enabled = true;

    /**
     * 每日最大使用量
     */
    private long dayMaxTokens;

    /**
     * 初始使用量
     */
    private long initTokens;

    /**
     * 管理员初始使用量
     */
    private long adminInitTokens;

    /**
     * 构造
     * 
     * @param enabled
     * @param dayMaxTokens
     */
    public DailyTokenQuota(boolean enabled, long dayMaxTokens) {
        this.enabled = enabled;
        this.dayMaxTokens = dayMaxTokens;
    }
}
