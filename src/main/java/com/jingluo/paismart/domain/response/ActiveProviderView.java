package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/18 17:28
 * @Desc: 当前激活的模型提供者视图，聚合客户端调用模型 API 所需的连接信息与参数
 */
@AllArgsConstructor
@Data
public class ActiveProviderView {

    /**
     * 提供者编码（如 deepseek / aliyun）
     */
    private String provider;

    /**
     * 提供者展示名称
     */
    private String displayName;

    /**
     * API 风格（如 openai-compatible）
     */
    private String apiStyle;

    /**
     * API 基础地址
     */
    private String apiBaseUrl;

    /**
     * 模型名称
     */
    private String model;

    /**
     * 解密后的 API 密钥
     */
    private String apiKey;

    /**
     * 向量维度，仅 embedding 作用域使用
     */
    private Integer dimension;
}
