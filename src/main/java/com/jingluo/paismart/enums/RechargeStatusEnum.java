package com.jingluo.paismart.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 充值支付状态枚举，对应微信支付的交易状态
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 11:15
 */
@Getter
@AllArgsConstructor
public enum RechargeStatusEnum {

    /**
     * 待支付
     */
    NOT_PAY(0, "待支付"),

    /**
     * 支付中
     */
    PAYING(1, "支付中"),

    /**
     * 支付成功
     */
    SUCCEED(2, "支付成功"),

    /**
     * 支付失败
     */
    FAIL(3, "支付失败");

    /**
     * 状态值
     */
    private Integer value;

    /**
     * 状态描述
     */
    private String desc;
}
