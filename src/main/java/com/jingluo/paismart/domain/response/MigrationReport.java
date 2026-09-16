package com.jingluo.paismart.domain.response;

import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 10:17
 * @Desc: MinIO 文件迁移结果报告，统计成功、跳过、失败数量并记录错误明细
 */
@Data
public class MigrationReport {

    /**
     * 迁移成功的文件数量
     */
    private int successCount = 0;

    /**
     * 跳过的文件数量（旧路径不存在或新路径已存在）
     */
    private int skipCount = 0;

    /**
     * 迁移失败的文件数量
     */
    private int errorCount = 0;

    /**
     * 错误明细，格式为“文件名: 错误信息”，多个错误以换行分隔
     */
    private StringBuilder errors = new StringBuilder();

    /**
     * 记录一次迁移成功
     */
    public void addSuccess() {
        successCount++;
    }

    /**
     * 记录一次跳过
     */
    public void addSkip() {
        skipCount++;
    }

    /**
     * 记录一次迁移失败
     *
     * @param fileName
     *            迁移失败的文件名
     * @param message
     *            错误信息
     */
    public void addError(String fileName, String message) {
        errorCount++;
        if (errors.length() > 0) {
            errors.append("\n");
        }
        errors.append(fileName).append(": ").append(message);
    }
}
