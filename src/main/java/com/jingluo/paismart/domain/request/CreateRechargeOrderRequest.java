package com.jingluo.paismart.domain.request;

import lombok.Data;

/**
 * 创建充值订单请求参数
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 9:25
 */
@Data
public class CreateRechargeOrderRequest {

    /**
     * 套餐 ID（可选，为空则为自定义充值）
     */
    private Integer packageId;

    /**
     * 自定义充值金额（单位分，仅在 packageId 为空时有效）
     */
    private Long customAmount;
}
