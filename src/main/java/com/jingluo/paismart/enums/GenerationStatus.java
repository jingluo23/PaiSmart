package com.jingluo.paismart.enums;

import lombok.Getter;

/**
 * 生成任务状态
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 16:45
 */
@Getter
public enum GenerationStatus {

    /**
     * 流式生成中
     */
    STREAMING,

    /**
     * 已完成
     */
    COMPLETED,

    /**
     * 生成失败
     */
    FAILED,

    /**
     * 已被用户或系统取消
     */
    CANCELLED;
}
