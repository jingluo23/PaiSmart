package com.jingluo.paismart.domain.response;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 14:28
 * @Desc: 用量总览
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UsageOverview {

    /**
     * 天数
     */
    private int days;

    /**
     * 日常使用量
     */
    private DailyUsagePoint today;

    /**
     * 使用趋势
     */
    private List<DailyUsagePoint> trends;

    /**
     * LLM使用量排名
     */
    private List<UsageRankingItem> llmRankings;

    /**
     * embedding使用量排名
     */
    private List<UsageRankingItem> embeddingRankings;

    /**
     * 使用提醒
     */
    private List<UsageAlert> alerts;
}
