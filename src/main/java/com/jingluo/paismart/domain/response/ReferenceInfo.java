package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 14:29
 * @Desc: AI 回答中的引用详情，描述某条回答依据的知识库检索片段及其溯源信息
 */
@AllArgsConstructor
@Data
public class ReferenceInfo {

    /**
     * 引用文件的 MD5 标识
     */
    private String fileMd5;

    /**
     * 引用文件名
     */
    private String fileName;

    /**
     * 引用内容所在 PDF 页码，非 PDF 文件或未知时为 null
     */
    private Integer pageNumber;

    /**
     * 页内定位锚点文本，用于前端跳转到对应位置
     */
    private String anchorText;

    /**
     * 检索模式（如向量检索、全文检索等）
     */
    private String retrievalMode;

    /**
     * 检索模式的展示名称
     */
    private String retrievalLabel;

    /**
     * 命中该引用时使用的检索查询语句
     */
    private String retrievalQuery;

    /**
     * 命中的分块原文
     */
    private String matchedChunkText;

    /**
     * 证据摘录片段
     */
    private String evidenceSnippet;

    /**
     * 检索相关性得分
     */
    private Double score;

    /**
     * 命中的分块序号
     */
    private Integer chunkId;
}
