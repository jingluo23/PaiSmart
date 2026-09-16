package com.jingluo.paismart.utils;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.jingluo.paismart.domain.response.MigrationReport;
import com.jingluo.paismart.model.FileUpload;
import com.jingluo.paismart.repository.FileUploadRepository;

import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 10:17
 * @Desc: MinIO 文件迁移工具，将 uploads 桶中按文件名存储的历史对象统一迁移为按文件 MD5 存储
 */
@Slf4j
@Component
public class MinioMigrationUtil {

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private MinioClient minioClient;

    /**
     * 迁移全部文件，遍历所有文件上传记录并逐一执行迁移
     *
     * @return 迁移结果报告，包含成功、跳过、失败数量及错误明细
     */
    public MigrationReport migrateAllFiles() {
        MigrationReport report = new MigrationReport();

        try {
            // 获取所有已完成上传的文件
            List<FileUpload> allFiles = fileUploadRepository.findAll();

            for (FileUpload file : allFiles) {
                migrateFile(file, report);
            }
        } catch (Exception e) {
            log.warn("迁移过程中发生错误", e);
        }

        return report;
    }

    /**
     * 迁移单个文件，路径由 "merged/文件名" 调整为 "merged/MD5"，旧路径不存在或新路径已存在时跳过
     *
     * @param file
     *            待迁移的文件上传记录
     * @param report
     *            迁移结果报告，用于记录本次操作结果
     */
    private void migrateFile(FileUpload file, MigrationReport report) {
        String oldPath = "merged/" + file.getFileName();
        String newPath = "merged/" + file.getFileMd5();

        try {
            // 1. 检查旧路径是否存在
            if (!objectExists(oldPath)) {
                report.addSkip();

                return;
            }

            // 2. 检查新路径是否已存在，已存在则只需清理旧路径
            if (objectExists(newPath)) {
                minioClient.removeObject(RemoveObjectArgs.builder().bucket("uploads").object(oldPath).build());

                report.addSkip();

                return;
            }

            // 3. 复制文件到新路径
            minioClient.copyObject(CopyObjectArgs.builder().bucket("uploads").object(newPath)
                .source(CopySource.builder().bucket("uploads").object(oldPath).build()).build());

            // 4. 删除旧路径
            minioClient.removeObject(RemoveObjectArgs.builder().bucket("uploads").object(oldPath).build());

            report.addSuccess();
        } catch (Exception e) {
            log.error("迁移失败: {}", file.getFileName(), e);
            report.addError(file.getFileName(), e.getMessage());
        }
    }

    /**
     * 判断指定对象是否存在于 uploads 桶中
     *
     * @param objectPath
     *            对象完整路径
     * @return 对象存在返回 true，不存在返回 false
     * @throws Exception
     *             网络、权限等非"对象不存在"原因的异常
     */
    private boolean objectExists(String objectPath) throws Exception {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket("uploads").object(objectPath).build());

            return Boolean.TRUE;
        } catch (ErrorResponseException e) {
            // 只有确认对象不存在才返回 false，网络/权限等其他错误继续抛出，避免误判跳过
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return Boolean.FALSE;
            }

            throw e;
        }
    }
}