package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 14:42
 * @Desc: 用量快照
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserUsageSnapshot {

    /**
     * 日期
     */
    private String day;

    /**
     * 会话请求数
     */
    private long chatRequestCount;

    /**
     * LLM使用量
     */
    private QuotaView llm;

    /**
     * embedding使用量
     */
    private QuotaView embedding;
}
