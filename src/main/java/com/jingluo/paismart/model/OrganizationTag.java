package com.jingluo.paismart.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 16:25
 * @Desc: 组织标签实体，支持树形层级结构，并可限制该标签下用户上传文件的大小上限
 */
// JPA 要求实体必须有 public/protected 无参构造，因下方存在自定义构造函数需显式补上
@NoArgsConstructor
@Data
@Entity
@Table(name = "organization_tags")
public class OrganizationTag {

    /**
     * 标签唯一标识
     */
    @Id
    @Column(name = "tag_id")
    private String tagId;

    /**
     * 标签名称
     */
    @Column(nullable = false)
    private String name;

    /**
     * 描述
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 父标签ID
     */
    @Column(name = "parent_tag", length = 255)
    private String parentTag;

    /**
     * 非管理员上传文件大小上限，null 表示不限制
     */
    @Column(name = "upload_max_size_bytes")
    private Long uploadMaxSizeBytes;

    /**
     * 创建者ID
     */
    @ManyToOne
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    /**
     * 创建时间
     */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * 全参构造函数，创建时间与更新时间由 Hibernate 自动填充
     *
     * @param tagId
     *            标签唯一标识
     * @param name
     *            标签名称
     * @param description
     *            标签描述
     * @param parentTag
     *            父标签 ID，为空表示顶级标签
     * @param uploadMaxSizeBytes
     *            非管理员上传文件大小上限（字节），null 表示不限制
     * @param createdBy
     *            创建者
     */
    public OrganizationTag(String tagId, String name, String description, String parentTag, Long uploadMaxSizeBytes,
        User createdBy) {
        this.tagId = tagId;
        this.name = name;
        this.description = description;
        this.parentTag = parentTag;
        this.uploadMaxSizeBytes = uploadMaxSizeBytes;
        this.createdBy = createdBy;
    }

    /**
     * 简化构造函数，不限制上传文件大小、无父标签（作为顶级标签）
     *
     * @param tagId
     *            标签唯一标识
     * @param name
     *            标签名称
     * @param description
     *            标签描述
     * @param createdBy
     *            创建者
     */
    public OrganizationTag(String tagId, String name, String description, User createdBy) {
        this(tagId, name, description, null, null, createdBy);
    }
}
