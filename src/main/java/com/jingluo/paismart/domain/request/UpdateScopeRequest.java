package com.jingluo.paismart.domain.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 更新模型提供者配置请求体
 *
 * @author 鲸落
 * @since 2026/9/14
 */
@Data
public class UpdateScopeRequest {

    /**
     * 当前启用的提供者名称
     */
    @NotBlank(message = "activeProvider不能为空")
    private String activeProvider;

    /**
     * 提供者配置列表
     */
    @NotEmpty(message = "providers不能为空")
    @Valid
    private List<ProviderUpsertRequest> providers;
}
