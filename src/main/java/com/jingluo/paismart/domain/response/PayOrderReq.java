package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 微信下单请求参数
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 10:33
 */
@AllArgsConstructor
@Data
public class PayOrderReq {

    /**
     * 业务单号
     */
    private String tradeNo;

    /**
     * 订单描述
     */
    private String description;

    /**
     * 订单金额，单位分
     */
    private int amount;
}
