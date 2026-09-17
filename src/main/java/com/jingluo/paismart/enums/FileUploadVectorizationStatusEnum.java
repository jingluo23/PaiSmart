package com.jingluo.paismart.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 16:43
 * @Desc: 文件向量化处理状态枚举
 */
@Getter
@AllArgsConstructor
public enum FileUploadVectorizationStatusEnum {

    /**
     * 待处理：文件已登记，尚未开始向量化
     */
    VECTORIZATION_STATUS_PENDING("PENDING"),

    /**
     * 处理中：向量化任务已派发，正在执行
     */
    VECTORIZATION_STATUS_PROCESSING("PROCESSING"),

    /**
     * 已完成：向量化执行成功
     */
    VECTORIZATION_STATUS_COMPLETED("COMPLETED"),

    /**
     * 失败：向量化执行失败，错误信息记录在 vectorizationErrorMessage
     */
    VECTORIZATION_STATUS_FAILED("FAILED");

    /**
     * 向量化状态标识
     */
    private String vectorizationStatus;
}
