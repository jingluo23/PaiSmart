package com.jingluo.paismart.domain.request;

import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 17:47
 * @Desc: 管理员为用户追加 Token 额度请求参数
 */
@Data
public class AddUserTokenRequest {

    /**
     * 追加的 LLM Token 数量（可选，为空表示不追加）
     */
    private Long llmToken;

    /**
     * 追加的 Embedding Token 数量（可选，为空表示不追加）
     */
    private Long embeddingToken;

    /**
     * 追加原因描述（可选，超长时截断至 200 字符）
     */
    private String reason;
}
