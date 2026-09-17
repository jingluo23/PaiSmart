package com.jingluo.paismart.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 14:29
 * @Desc: 文件上传状态枚举
 */
@Getter
@AllArgsConstructor
public enum FileUploadStatusEnum {

    /**
     * 上传中：分片正在上传，尚未合并
     */
    STATUS_UPLOADING(0, "上传中"),

    /**
     * 已完成：分片已全部合并
     */
    STATUS_COMPLETED(1, "已完成"),

    /**
     * 合并中：分片合并正在进行，拒绝新的分片上传
     */
    STATUS_MERGING(2, "合并中");

    /**
     * 状态值，持久化到数据库
     */
    private int value;

    /**
     * 状态描述
     */
    private String desc;
}
