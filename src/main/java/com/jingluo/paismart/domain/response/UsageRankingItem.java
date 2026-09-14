package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 14:34
 * @Desc: 用量排名项
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UsageRankingItem {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 模型名称
     */
    private String scope;

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
