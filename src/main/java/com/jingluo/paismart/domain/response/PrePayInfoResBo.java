package com.jingluo.paismart.domain.response;

import lombok.Data;

/**
 * 微信预支付信息响应对象，返回给前端用于唤起支付
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 9:40
 */
@Data
public class PrePayInfoResBo {

    /**
     * 传递给三方的外部系统编号
     */
    private String outTradeNo;

    /**
     * 应用: appId
     */
    private String appId;

    /**
     * 时间戳信息
     */
    private String nonceStr;

    /**
     * 小程序/JSAPI 支付所需的 package 参数
     */
    private String prePackage;

    /**
     * 签名
     */
    private String paySign;

    /**
     * 时间戳
     */
    private String timeStamp;

    /**
     * 签名类型
     */
    private String signType;

    /**
     * jsapi：返回的是用于唤起支付的 prePayId h5: 返回的是微信收银台中间页 url，用于访问之后唤起微信客户端的支付页面 native: 返回的是形如 weixin:// 的文本，用于生成二维码给微信扫一扫支付
     */
    private String prePayId;

    /**
     * prePayId的失效的时间戳
     */
    private Long expireTime;
}
