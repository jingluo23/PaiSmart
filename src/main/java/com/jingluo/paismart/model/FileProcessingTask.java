package com.jingluo.paismart.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 16:37
 * @Desc: Kafka 文件处理任务消息体：携带文件信息、权限归属与任务类型
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileProcessingTask {

    /**
     * 文件的 MD5 校验值
     */
    private String fileMd5;

    /**
     * 文件存储路径
     */
    private String filePath;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 上传用户ID
     */
    private String userId;

    /**
     * 文件所属组织标签
     */
    private String orgTag;

    /**
     * 文件是否公开
     */
    private boolean isPublic;

    /**
     * 任务类型
     */
    private String taskType;

    /**
     * 发起重试的用户
     */
    private String requesterId;
}
