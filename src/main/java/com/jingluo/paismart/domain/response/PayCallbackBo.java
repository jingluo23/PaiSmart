package com.jingluo.paismart.domain.response;

import com.jingluo.paismart.enums.RechargeStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 微信支付回调结果业务对象，由回调报文解析而来
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 11:13
 */
@AllArgsConstructor
@Data
public class PayCallbackBo {

    /**
     * 传递给支付系统的唯一外部单号
     */
    private String outTradeNo;

    /**
     * 支付成功时间
     */
    private Long successTime;

    /**
     * 三方流水编号
     */
    private String thirdTransactionId;

    /**
     * 支付状态
     */
    private RechargeStatusEnum payStatus;
}
