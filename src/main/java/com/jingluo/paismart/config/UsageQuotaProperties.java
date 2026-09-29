package com.jingluo.paismart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.jingluo.paismart.domain.response.DailyTokenQuota;

import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 14:58
 * @Desc: 用量配额属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "usage-quota")
public class UsageQuotaProperties {

    /**
     * 存储数据保留天数
     */
    private int retentionDays = 35;

    /**
     * LLM 用量配额
     */
    private DailyTokenQuota llm = new DailyTokenQuota(true, 300_000);

    /**
     * Embedding 用量配额
     */
    private DailyTokenQuota embedding = new DailyTokenQuota(true, 1_000_000);
}
