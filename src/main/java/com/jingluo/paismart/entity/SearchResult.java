package com.jingluo.paismart.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/20 9:07
 * @Desc: 搜索结果条目，对应一个命中的文档分块及其文件溯源信息
 */
@AllArgsConstructor
@Data
public class SearchResult {

    /**
     * 文件指纹
     */
    private String fileMd5;

    /**
     * 文本分块序号
     */
    private Integer chunkId;

    /**
     * 文本内容
     */
    private String textContent;

    /**
     * 搜索得分
     */
    private Double score;

    /**
     * 原始文件名
     */
    private String fileName;

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
    private Boolean isPublic;

    /**
     * PDF 页码
     */
    private Integer pageNumber;

    /**
     * 页内定位锚点
     */
    private String anchorText;

    /**
     * 召回方式
     */
    private String retrievalMode;

    /**
     * 命中的 chunk 原文
     */
    private String matchedChunkText;

    /**
     * 全字段构造函数：fileName 允许为空，由检索服务异步补齐； matchedChunkText 为空时回退为文本内容，保证高亮来源始终可用
     */
    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag,
        boolean isPublic, String fileName, Integer pageNumber, String anchorText, String retrievalMode,
        String matchedChunkText) {
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.score = score;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.fileName = fileName;
        this.pageNumber = pageNumber;
        this.anchorText = anchorText;
        this.retrievalMode = retrievalMode;
        this.matchedChunkText = matchedChunkText != null ? matchedChunkText : textContent;
    }
}
