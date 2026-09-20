package com.jingluo.paismart.domain.response;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/18 17:42
 * @Desc: Embedding 调用结果，包含向量、实际消耗 Token 与所用的模型版本
 */
@AllArgsConstructor
@Data
public class EmbeddingUsageResult {

    /**
     * 与输入文本顺序一一对应的向量列表
     */
    private List<float[]> vectors;

    /**
     * 本次调用实际消耗的 Token 总数
     */
    private int totalTokens;

    /**
     * 向量模型版本标识（provider:model:dimension），用于向量库中标识向量的生成版本
     */
    private String modelVersion;
}
