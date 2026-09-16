package com.jingluo.paismart.domain.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 充值套餐创建/更新请求参数
 *
 * @Author: 鲸落
 * @Date: 2026/9/16 11:37
 */
@Data
public class RechargePackageRequest {

    /**
     * 套餐名称
     */
    @NotBlank(message = "套餐名称不能为空")
    private String packageName;

    /**
     * 套餐价格，单位分
     */
    @NotNull(message = "套餐价格不能为空")
    @Min(value = 0, message = "套餐价格必须大于 0")
    private Long packagePrice;

    /**
     * 套餐描述
     */
    private String packageDesc;

    /**
     * 套餐权益
     */
    private String packageBenefit;

    /**
     * LLM token 数量
     */
    @NotNull(message = "LLM token 不能为空")
    @Min(value = 0, message = "LLM token 必须大于 0")
    private Long llmToken;

    /**
     * Embedding token 数量
     */
    @NotNull(message = "Embedding token 不能为空")
    @Min(value = 0, message = "Embedding token 必须大于 0")
    private Long embeddingToken;

    /**
     * 是否启用（为空时默认启用）
     */
    private Boolean enabled;

    /**
     * 排序顺序，数字越小越靠前（为空时默认 0）
     */
    private Integer sortOrder;
}
