package com.jingluo.paismart.domain.response;

import lombok.Data;

/**
 * 流式响应的 token 用量追踪器
 * <p>
 * 在流式调用期间聚合增量内容与 usage 统计，流结束后据此对预预留的配额进行结算。
 * 字段使用 volatile：chunk 回调与结算可能发生在不同线程。
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 17:36
 */
@Data
public class StreamUsageTracker {

    /**
     * 调用前预预留的 token 配额
     */
    private final TokenReservation reservation;

    /**
     * 发起请求前估算的 prompt token 数
     */
    private final int estimatedPromptTokens;

    /**
     * 聚合的流式增量内容
     */
    private final StringBuilder responseContent = new StringBuilder();

    /**
     * 流式 usage 上报的实际 prompt token 数
     */
    private volatile int promptTokens;

    /**
     * 流式 usage 上报的实际 completion token 数
     */
    private volatile int completionTokens;

    /**
     * 是否已完成结算，防止重复结算
     */
    private volatile boolean settled;

    public StreamUsageTracker(TokenReservation reservation, int estimatedPromptTokens) {
        this.reservation = reservation;
        this.estimatedPromptTokens = estimatedPromptTokens;
    }
}
