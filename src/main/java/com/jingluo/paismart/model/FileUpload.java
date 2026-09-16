package com.jingluo.paismart.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 10:23
 * @Desc: 文件上传记录实体，跟踪分片上传状态，并记录向量化进度与 Token 消耗
 */
@Data
@Entity
@Table(name = "file_upload",
    uniqueConstraints = @UniqueConstraint(name = "uk_file_upload_md5_user", columnNames = {"file_md5", "user_id"}))
public class FileUpload {

    /**
     * 文件的唯一标识符 使用文件的MD5值来唯一确定一个文件
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 文件的MD5值 与用户ID组成唯一约束，用于秒传判断
     */
    @Column(name = "file_md5", length = 32, nullable = false)
    private String fileMd5;

    /**
     * 文件的原始名称 用于记录上传时文件的名称
     */
    private String fileName;

    /**
     * 文件的总大小 以字节为单位记录文件的大小
     */
    private long totalSize;

    /**
     * 文件上传的状态 0表示文件正在上传中，1表示文件上传已完成，2表示文件正在合并中
     */
    private int status;

    /**
     * 上传文件的用户的标识符 用于记录哪个用户上传了文件
     */
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    /**
     * 文件所属组织标签 用于标识文件归属的组织，支持基于组织标签的权限控制
     */
    @Column(name = "org_tag")
    private String orgTag;

    /**
     * 文件是否公开 true表示所有用户可访问，false表示仅组织内用户可访问
     */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    /**
     * 预估的向量化 Embedding Token 消耗
     */
    @Column(name = "estimated_embedding_tokens")
    private Long estimatedEmbeddingTokens;

    /**
     * 预估的文件分块数量
     */
    @Column(name = "estimated_chunk_count")
    private Integer estimatedChunkCount;

    /**
     * 实际向量化消耗的 Embedding Token 数量
     */
    @Column(name = "actual_embedding_tokens")
    private Long actualEmbeddingTokens;

    /**
     * 实际的文件分块数量
     */
    @Column(name = "actual_chunk_count")
    private Integer actualChunkCount;

    /**
     * 向量化任务状态，如 PENDING、PROCESSING、COMPLETED、FAILED
     */
    @Column(name = "vectorization_status", length = 32)
    private String vectorizationStatus;

    /**
     * 向量化失败时的错误信息
     */
    @Column(name = "vectorization_error_message", length = 1000)
    private String vectorizationErrorMessage;

    /**
     * 文件上传的创建时间 自动记录文件上传开始的时间
     */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * 文件合并完成的时间 当文件上传状态为已完成时，自动记录完成的时间
     */
    @UpdateTimestamp
    private LocalDateTime mergedAt;
}
