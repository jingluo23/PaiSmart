package com.jingluo.paismart.enums;

import lombok.Getter;

/**
 * 充值订单状态枚举
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 10:23
 */
@Getter
public enum OrderStatus {

    /**
     * 待支付
     */
    NOT_PAY,

    /**
     * 支付中
     */
    PAYING,

    /**
     * 支付成功
     */
    SUCCEED,

    /**
     * 支付失败
     */
    FAIL,

    /**
     * 已取消
     */
    CANCELLED;
}
