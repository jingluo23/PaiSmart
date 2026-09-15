package com.jingluo.paismart.domain.request;

import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 15:21
 * @Desc: 管理员创建用户请求体
 */
@Data
public class AdminUserRequest {

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;
}
