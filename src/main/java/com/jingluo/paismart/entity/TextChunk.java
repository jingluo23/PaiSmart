package com.jingluo.paismart.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 9:28
 * @Desc: 文档解析后的文本分块，作为向量化与检索的最小处理单元
 */
@AllArgsConstructor
@Data
public class TextChunk {

    /**
     * 分块序号
     */
    private int chunkId;

    /**
     * 分块内容
     */
    private String content;

    /**
     * PDF 页码
     */
    private Integer pageNumber;

    /**
     * 页内定位锚点
     */
    private String anchorText;
}
