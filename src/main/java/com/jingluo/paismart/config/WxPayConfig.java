package com.jingluo.paismart.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 微信支付配置类，映射 yml 中 wx.pay 前缀的配置项
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 9:32
 */
@Data
@Component
@ConfigurationProperties(prefix = "wx.pay")
public class WxPayConfig {

    /**
     * 是否启用微信支付
     */
    private boolean enable = false;

    /**
     * APPID
     */
    private String appId;

    /**
     * mchid
     */
    private String merchantId;

    /**
     * 商户API私钥
     */
    private String privateKey;

    /**
     * 商户证书序列号
     */
    private String merchantSerialNumber;

    /**
     * 商户APIv3密钥
     */
    private String apiV3Key;

    /**
     * 支付通知地址
     */
    private String payNotifyUrl;

    /**
     * 退款通知地址
     */
    private String refundNotifyUrl;

    public String getPrivateKeyContent() {
        if (StringUtils.isNotBlank(privateKey) && privateKey.contains("-----BEGIN PRIVATE KEY")) {
            // 私钥内容是直接以文本的方式提供的，直接返回
            return privateKey;
        }

        if (StringUtils.isNotBlank(privateKey) && (privateKey.endsWith("=") || privateKey.length() > 200)) {
            // 如果是base64编码的传入方式, 使用base64进行解码
            return new String(Base64.decodeBase64(privateKey), StandardCharsets.UTF_8);
        }

        // 私钥是以文件的方式提供
        try {
            return IOUtils.resourceToString(privateKey, StandardCharsets.UTF_8, this.getClass().getClassLoader());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
