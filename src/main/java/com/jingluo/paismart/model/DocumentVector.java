package com.jingluo.paismart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 17:32
 * @Desc:
 */
@Data
@Entity
@Table(name = "document_vectors")
public class DocumentVector {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long vectorId;

    @Column(nullable = false, length = 32)
    private String fileMd5;

    @Column(nullable = false)
    private Integer chunkId;

    @Lob
    private String textContent;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "anchor_text", length = 512)
    private String anchorText;

    @Column(length = 32)
    private String modelVersion;

    /**
     * 上传用户ID
     */
    @Column(nullable = false, name = "user_id", length = 64)
    private String userId;

    /**
     * 文件所属组织标签
     */
    @Column(name = "org_tag", length = 50)
    private String orgTag;

    /**
     * 文件是否公开
     */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;
}
