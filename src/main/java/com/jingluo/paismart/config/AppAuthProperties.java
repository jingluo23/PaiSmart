package com.jingluo.paismart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.jingluo.paismart.domain.response.Registration;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 16:24
 * @Desc: 应用认证配置属性，绑定 app.auth 前缀的配置项（如注册策略）
 */
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AppAuthProperties {

    /**
     * 注册策略配置，默认仅允许邀请码注册
     */
    private final Registration registration = new Registration();

    public Registration getRegistration() {
        return registration;
    }
}
