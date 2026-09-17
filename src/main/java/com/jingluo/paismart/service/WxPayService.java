package com.jingluo.paismart.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.config.WxPayConfig;
import com.jingluo.paismart.domain.response.PayCallbackBo;
import com.jingluo.paismart.domain.response.PayOrderReq;
import com.jingluo.paismart.domain.response.PrePayInfoResBo;
import com.jingluo.paismart.enums.RechargeStatusEnum;
import com.jingluo.paismart.utils.HttpRequestUtil;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.Amount;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayResponse;
import com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.nativepay.model.SceneInfo;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 微信支付服务，封装 Native 下单、支付回调验签解析与订单查询
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 9:29
 */
@Slf4j
@Service
public class WxPayService {

    @Autowired
    private WxPayConfig wxPayConfig;

    @Autowired
    private NativePayService nativePayService;

    /**
     * 订单过期时间，官方默认有效期为2小时，这里我们设置为100分钟
     */
    private final static int PAY_EXPIRE_TIME = 100 * 60 * 1000;

    /**
     * 微信支付日期格式
     */
    public static final DateTimeFormatter WX_PAY_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+08:00'");

    /**
     * 构造方法：启用微信支付时初始化 Native 支付服务（自动更新平台证书），未启用时置为 null
     */
    public WxPayService(WxPayConfig wxPayConfig) {
        this.wxPayConfig = wxPayConfig;
        if (wxPayConfig.isEnable()) {
            Config config = new RSAAutoCertificateConfig.Builder().merchantId(wxPayConfig.getMerchantId())
                .privateKey(wxPayConfig.getPrivateKeyContent())
                .merchantSerialNumber(wxPayConfig.getMerchantSerialNumber()).apiV3Key(wxPayConfig.getApiV3Key())
                .build();
            nativePayService = new NativePayService.Builder().config(config).build();
        } else {
            nativePayService = null;
        }
    }

    /**
     * 调用微信 Native 下单接口创建支付订单
     *
     * @param payReq
     *            下单请求参数（业务单号、描述、金额）
     * @return 预支付信息，prePayId 为用于生成支付二维码的 codeUrl
     */
    public PrePayInfoResBo createOrder(PayOrderReq payReq) {
        // 微信下单
        String prePayId = createPayOrder(payReq);

        return buildPayInfo(payReq, prePayId);
    }

    /**
     * 封装预支付信息响应对象
     */
    private PrePayInfoResBo buildPayInfo(PayOrderReq payReq, String prePayId) {
        // 结果封装返回
        PrePayInfoResBo prePay = new PrePayInfoResBo();
        prePay.setOutTradeNo(payReq.getTradeNo());
        prePay.setAppId(wxPayConfig.getAppId());
        prePay.setPrePayId(prePayId);
        prePay.setExpireTime(System.currentTimeMillis() + PAY_EXPIRE_TIME);

        return prePay;
    }

    /**
     * 调用微信下单接口，返回 Native 支付二维码链接 codeUrl
     */
    private String createPayOrder(PayOrderReq payReq) {
        PrepayRequest request = new PrepayRequest();
        request.setAppid(wxPayConfig.getAppId());
        request.setMchid(wxPayConfig.getMerchantId());
        request.setDescription(payReq.getDescription());
        request.setNotifyUrl(wxPayConfig.getPayNotifyUrl());
        request.setOutTradeNo(payReq.getTradeNo());

        Amount amount = new Amount();
        amount.setTotal(payReq.getAmount());
        amount.setCurrency("CNY");
        request.setAmount(amount);

        SceneInfo sceneInfo = new SceneInfo();
        sceneInfo.setPayerClientIp(HttpRequestUtil.getClientIp());
        request.setSceneInfo(sceneInfo);

        if (Objects.isNull(nativePayService)) {
            throw new ValidationException("微信支付未启用");
        }

        PrepayResponse response = nativePayService.prepay(request);

        return response.getCodeUrl();
    }

    /**
     * 处理微信支付结果通知：使用请求头中的签名信息验签、解密回调报文，转换为支付回调业务对象
     */
    public PayCallbackBo payCallback(HttpServletRequest request) {
        RequestParam requestParam = new RequestParam.Builder().serialNumber(request.getHeader("Wechatpay-Serial"))
            .nonce(request.getHeader("Wechatpay-Nonce")).timestamp(request.getHeader("Wechatpay-Timestamp"))
            .signature(request.getHeader("Wechatpay-Signature")).body(HttpRequestUtil.readReqData(request)).build();

        NotificationConfig config = new RSAAutoCertificateConfig.Builder().merchantId(wxPayConfig.getMerchantId())
            .privateKey(wxPayConfig.getPrivateKeyContent()).merchantSerialNumber(wxPayConfig.getMerchantSerialNumber())
            .apiV3Key(wxPayConfig.getApiV3Key()).build();

        NotificationParser parser = new NotificationParser(config);
        // 验签、解密并转换成 Transaction（返回参数对象）
        Transaction transaction = parser.parse(requestParam, Transaction.class);

        return toBo(transaction);
    }

    /**
     * 将微信交易对象转换为支付回调业务对象，交易状态映射为充值支付状态枚举
     */
    private PayCallbackBo toBo(Transaction transaction) {
        String outTradeNo = transaction.getOutTradeNo();

        RechargeStatusEnum payStatus = switch (transaction.getTradeState()) {
            case SUCCESS:
                yield RechargeStatusEnum.SUCCEED;
            case NOTPAY:
                yield RechargeStatusEnum.NOT_PAY;
            case USERPAYING:
                yield RechargeStatusEnum.PAYING;
            default:
                yield RechargeStatusEnum.FAIL;
        };

        Long payTime = StringUtils.isNotBlank(transaction.getSuccessTime())
            ? wxDayToTimestamp(transaction.getSuccessTime()) : null;

        return new PayCallbackBo(outTradeNo, payTime, transaction.getTransactionId(), payStatus);
    }

    /**
     * 将微信支付时间字符串（如 2018-06-08T10:34:56+08:00）转换为毫秒级时间戳
     */
    public static Long wxDayToTimestamp(String day) {
        LocalDateTime parse = LocalDateTime.parse(day, WX_PAY_FORMATTER);

        return parse.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 根据业务单号主动查询微信侧订单支付状态
     */
    public PayCallbackBo queryOrder(String outTradeNo) {
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(wxPayConfig.getMerchantId());
        request.setOutTradeNo(outTradeNo);
        Transaction transaction = nativePayService.queryOrderByOutTradeNo(request);

        return toBo(transaction);
    }
}
