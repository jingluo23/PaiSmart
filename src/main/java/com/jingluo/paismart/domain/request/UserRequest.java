package com.jingluo.paismart.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 16:06
 * @Desc: 用户注册 / 登录请求体
 */
@Data
public class UserRequest {

    /**
     * 用户名
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 密码（明文）
     */
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 邀请码（注册模式要求邀请码时必填）
     */
    private String inviteCode;
}
