package com.jingluo.paismart.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 模型提供者新增/更新请求体
 *
 * @author 鲸落
 * @since 2026/9/14
 */
@Data
public class ProviderUpsertRequest {

    /**
     * 提供者名称，如 openai、qwen
     */
    @NotBlank(message = "provider不能为空")
    private String provider;

    /**
     * API 基础地址；关闭的 provider 允许为空，由服务层保留现有配置
     */
    private String apiBaseUrl;

    /**
     * 模型名称；关闭的 provider 允许为空，由服务层保留现有配置
     */
    private String model;

    /**
     * API 密钥；为空时服务层保留现有密文
     */
    private String apiKey;

    /**
     * 向量维度；关闭的 provider 允许为空
     */
    private Integer dimension;

    /**
     * 是否启用
     */
    @NotNull(message = "enabled不能为空")
    private Boolean enabled;
}
