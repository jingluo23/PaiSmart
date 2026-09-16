package com.jingluo.paismart.utils;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.jingluo.paismart.domain.response.MigrationReport;
import com.jingluo.paismart.model.FileUpload;
import com.jingluo.paismart.repository.FileUploadRepository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
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

    @Autowired
    private ElasticsearchClient elasticsearchClient;

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
            log.warn("迁移失败: {}", file.getFileName(), e);

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

    /**
     * 清空全部业务数据：按顺序删除 ES knowledge_base 索引的全部文档、清空 MySQL 文件上传记录表， 再依据文件记录清理 MinIO uploads 桶 merged 目录下按 MD5 与按文件名存储的两类对象
     */
    public void clearAllData() {
        try {
            // 1. 清空 ElasticSearch
            DeleteByQueryRequest deleteRequest =
                DeleteByQueryRequest.of(d -> d.index("knowledge_base").query(Query.of(q -> q.matchAll(m -> m))));
            elasticsearchClient.deleteByQuery(deleteRequest);

            // 2. 清空 MySQL 表
            fileUploadRepository.deleteAll();

            // 3. 清空 MinIO merged 目录
            List<FileUpload> files = fileUploadRepository.findAll();
            for (FileUpload file : files) {
                try {
                    minioClient.removeObject(
                        RemoveObjectArgs.builder().bucket("uploads").object("merged/" + file.getFileMd5()).build());
                } catch (Exception e) {
                    // 忽略错误
                }
                try {
                    minioClient.removeObject(
                        RemoveObjectArgs.builder().bucket("uploads").object("merged/" + file.getFileName()).build());
                } catch (Exception e) {
                    // 忽略错误
                }
            }
        } catch (Exception e) {
            log.warn("清空数据时出错", e);
        }
    }
}