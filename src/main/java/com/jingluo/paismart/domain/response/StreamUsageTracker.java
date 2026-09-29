package com.jingluo.paismart.domain.response;

import java.util.concurrent.atomic.AtomicBoolean;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

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
     * 结算标记：usage 只允许结算一次；chunk 回调与超时/取消路径可能并发结算，需 CAS 抢占
     */
    @Getter(AccessLevel.NONE)
    private final AtomicBoolean settled = new AtomicBoolean(false);

    public StreamUsageTracker(TokenReservation reservation, int estimatedPromptTokens) {
        this.reservation = reservation;
        this.estimatedPromptTokens = estimatedPromptTokens;
    }

    /**
     * CAS 抢占结算权：返回 true 表示本次调用获得结算资格，false 表示已被并发路径结算
     */
    public boolean markSettled() {
        return settled.compareAndSet(false, true);
    }

    /**
     * 是否已完成结算
     */
    public boolean isSettled() {
        return settled.get();
    }
}
