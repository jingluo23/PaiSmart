package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 15:06
 * @Desc:
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyUsageAggregate {

    /**
     * 日期
     */
    private String day;

    /**
     * 聊天请求数
     */
    private long chatRequestCount;

    /**
     * LLM使用token数
     */
    private long llmUsedTokens;

    /**
     * LLM请求数
     */
    private long llmRequestCount;

    /**
     * embedding使用token数
     */
    private long embeddingUsedTokens;

    /**
     * embedding请求数
     */
    private long embeddingRequestCount;
}
