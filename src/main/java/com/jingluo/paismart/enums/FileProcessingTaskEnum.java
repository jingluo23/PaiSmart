package com.jingluo.paismart.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 16:40
 * @Desc: 文件处理任务类型枚举
 */
@AllArgsConstructor
@Getter
public enum FileProcessingTaskEnum {

    /**
     * 上传后处理任务：文件合并完成后触发解析与向量化
     */
    TASK_TYPE_UPLOAD_PROCESS("UPLOAD_PROCESS"),

    /**
     * 重建索引任务：对已有文件重新执行解析与向量化
     */
    TASK_TYPE_REINDEX("REINDEX");

    /**
     * 任务类型标识
     */
    private String taskType;
}
