package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 9:21
 * @Desc: 向量化实际用量结果，记录文档向量化过程中消耗的 Tokens、分块数及所用模型版本
 */
@AllArgsConstructor
@Data
public class VectorizationUsageResult {

    /**
     * 实际消耗的 Embedding Tokens 数
     */
    private int actualEmbeddingTokens;

    /**
     * 实际生成的分块数量
     */
    private int actualChunkCount;

    /**
     * 本次向量化使用的 Embedding 模型版本
     */
    private String modelVersion;
}
