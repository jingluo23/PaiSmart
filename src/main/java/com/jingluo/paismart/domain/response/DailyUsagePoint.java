package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 14:30
 * @Desc: 日常使用量
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DailyUsagePoint {

    /**
     * 日期
     */
    private String day;

    /**
     * 请求次数
     */
    private long chatRequestCount;

    /**
     * LLM使用token
     */
    private long llmUsedTokens;

    /**
     * LLM请求次数
     */
    private long llmRequestCount;

    /**
     * embedding使用token
     */
    private long embeddingUsedTokens;

    /**
     * embedding请求次数
     */
    private long embeddingRequestCount;
}
