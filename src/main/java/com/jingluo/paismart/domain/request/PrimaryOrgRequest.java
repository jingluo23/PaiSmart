package com.jingluo.paismart.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 16:56
 * @Desc: 设置用户主组织请求体
 */
@Data
public class PrimaryOrgRequest {

    /**
     * 主组织标签 ID
     */
    @NotBlank(message = "主组织不能为空")
    private String primaryOrg;
}
