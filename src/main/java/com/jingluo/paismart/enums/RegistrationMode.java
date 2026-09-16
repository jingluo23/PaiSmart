package com.jingluo.paismart.enums;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 16:26
 * @Desc: 用户注册模式枚举
 */
public enum RegistrationMode {

    /**
     * 开放注册，无需邀请码
     */
    OPEN,

    /**
     * 仅允许凭有效邀请码注册
     */
    INVITE_ONLY,

    /**
     * 关闭注册，禁止任何新用户注册
     */
    CLOSED;
}
