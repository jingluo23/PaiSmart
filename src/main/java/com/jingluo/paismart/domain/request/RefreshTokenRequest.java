package com.jingluo.paismart.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 14:56
 * @Desc: 刷新令牌请求体
 */
@Data
public class RefreshTokenRequest {

    /**
     * 刷新令牌，用于换取新的 accessToken 和 refreshToken
     */
    @NotBlank(message = "refreshToken不能为空")
    private String refreshToken;
}
