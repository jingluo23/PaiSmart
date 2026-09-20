package com.jingluo.paismart.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/20 9:01
 * @Desc: 知识库 ES 文档实体，对应 knowledge_base 索引中一个文件分块， 携带向量、来源溯源与权限字段
 */
@AllArgsConstructor
@Data
public class EsDocument {

    /**
     * 文档唯一标识
     */
    private String id;

    /**
     * 文件指纹
     */
    private String fileMd5;

    /**
     * 文档分块序号
     */
    private Integer chunkId;

    /**
     * 文本内容
     */
    private String textContent;

    /**
     * PDF 页码
     */
    private Integer pageNumber;

    /**
     * 页内定位锚点
     */
    private String anchorText;

    /**
     * 向量数据（768维）
     */
    private float[] vector;

    /**
     * 向量生成模型版本
     */
    private String modelVersion;

    /**
     * 上传用户ID
     */
    private String userId;

    /**
     * 组织标签
     */
    private String orgTag;

    /**
     * 是否公开
     */
    private boolean isPublic;
}
