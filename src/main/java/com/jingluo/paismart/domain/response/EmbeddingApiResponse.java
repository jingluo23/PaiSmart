package com.jingluo.paismart.domain.response;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/18 17:38
 * @Desc: Embedding API 原始响应的解析结果
 */
@AllArgsConstructor
@Data
public class EmbeddingApiResponse {

    /**
     * 与输入文本顺序一一对应的向量列表
     */
    private List<float[]> vectors;

    /**
     * 本次请求消耗的 Token 总数，API 未返回时以本地估算值兜底
     */
    private int totalTokens;
}
