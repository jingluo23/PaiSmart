package com.jingluo.paismart.enums;

import lombok.Getter;

/**
 * @Author: 鲸落
 * @Date: 2026/9/20 11:05
 * @Desc: 对话会话状态枚举，定义会话的生命周期状态
 */
@Getter
public enum SessionStatusEnum {

    /**
     * 活跃状态，会话可正常收发消息
     */
    ACTIVE,

    /**
     * 已归档状态，归档后不在默认会话列表中展示
     */
    ARCHIVED;
}
