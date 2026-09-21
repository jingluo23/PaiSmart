package com.jingluo.paismart.domain.response;

import lombok.Data;

/**
 * LLM 生成参数配置（ai.generation.*）
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 17:34
 */
@Data
public class Generation {

    /**
     * 采样温度
     */
    private Double temperature = 0.3;

    /**
     * 最大输出 tokens
     */
    private Integer maxTokens = 2000;

    /**
     * nucleus top-p
     */
    private Double topP = 0.9;
}
