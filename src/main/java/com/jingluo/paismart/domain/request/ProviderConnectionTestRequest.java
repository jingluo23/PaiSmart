package com.jingluo.paismart.domain.request;

import lombok.Data;

/**
 * 模型提供者连通性测试请求体
 *
 * @Author: jingluo
 * @Date: 2026/9/15 14:40
 * @Desc: 管理端测试指定模型提供者配置是否可用的请求参数
 */
@Data
public class ProviderConnectionTestRequest {

    /**
     * 提供者名称，如 openai、qwen
     */
    private String provider;

    /**
     * API 基础地址
     */
    private String apiBaseUrl;

    /**
     * 模型名称
     */
    private String model;

    /**
     * API 密钥
     */
    private String apiKey;

    /**
     * 向量维度
     */
    private Integer dimension;
}
