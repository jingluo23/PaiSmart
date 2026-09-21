package com.jingluo.paismart.domain.response;

import lombok.Data;

/**
 * 提示词相关配置（ai.prompt.*）
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 17:33
 */
@Data
public class Prompt {

    /**
     * 规则文案
     */
    private String rules;

    /**
     * 引用开始分隔符
     */
    private String refStart;

    /**
     * 引用结束分隔符
     */
    private String refEnd;

    /**
     * 无检索结果时的占位文案
     */
    private String noResultText;
}
