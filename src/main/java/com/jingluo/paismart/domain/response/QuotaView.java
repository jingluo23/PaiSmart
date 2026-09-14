package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 14:43
 * @Desc: 用量视图
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class QuotaView {

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 用量
     */
    private long usedTokens;

    /**
     * 限制量
     */
    private long limitTokens;

    /**
     * 剩余量
     */
    private long remainingTokens;

    /**
     * 请求次数
     */
    private long requestCount;
}
