package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 16:31
 * @Desc: 模型提供者配置
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderConfigView {

    /**
     * 模型提供者
     */
    private String provider;

    /**
     * 模型提供者显示名称
     */
    private String displayName;

    /**
     * API风格
     */
    private String apiStyle;

    /**
     * API基础URL
     */
    private String apiBaseUrl;

    /**
     * 模型名称
     */
    private String model;

    /**
     * 模型维度
     */
    private Integer dimension;

    /**
     * 是否启用
     */
    private boolean enabled;

    /**
     * 是否激活
     */
    private boolean active;

    /**
     * 是否有API密钥
     */
    private boolean hasApiKey;

    /**
     * 密钥
     */
    private String maskedApiKey;
}
