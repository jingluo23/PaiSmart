package com.jingluo.paismart.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 17:04
 * @Desc: 模型提供者配置实体，对应 model_provider_configs 表
 */
@Data
@Entity
@Table(name = "model_provider_configs",
    indexes = {@Index(name = "idx_model_provider_scope", columnList = "config_scope"),
        @Index(name = "idx_model_provider_scope_provider", columnList = "config_scope,provider_code", unique = true)})
public class ModelProviderConfig {

    /**
     * 主键ID，自增
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 配置作用域（llm / embedding）
     */
    @Column(name = "config_scope", nullable = false, length = 32)
    private String configScope;

    /**
     * 提供者编码（同作用域内唯一）
     */
    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    /**
     * 提供者显示名称
     */
    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    /**
     * API风格
     */
    @Column(name = "api_style", nullable = false, length = 64)
    private String apiStyle;

    /**
     * API基础URL
     */
    @Column(name = "api_base_url", nullable = false, length = 512)
    private String apiBaseUrl;

    /**
     * 模型名称
     */
    @Column(name = "model_name", nullable = false, length = 255)
    private String modelName;

    /**
     * API密钥密文（加密存储）
     */
    @Column(name = "api_key_ciphertext", length = 2048)
    private String apiKeyCiphertext;

    /**
     * 向量维度（embedding 作用域使用）
     */
    @Column(name = "embedding_dimension")
    private Integer embeddingDimension;

    /**
     * 是否启用
     */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * 是否为该作用域当前激活的提供者
     */
    @Column(name = "active", nullable = false)
    private boolean active = false;

    /**
     * 最后更新人
     */
    @Column(name = "updated_by", nullable = false, length = 255)
    private String updatedBy;

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
}
