package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 15:33
 * @Desc: Embedding 用量估算结果
 */
@AllArgsConstructor
@Data
public class EmbeddingEstimate {

    /**
     * 估算的 Embedding Token 总数
     */
    private long estimatedTokens;

    /**
     * 估算的分块数量
     */
    private int estimatedChunkCount;
}
