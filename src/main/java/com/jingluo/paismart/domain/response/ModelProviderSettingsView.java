package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 16:28
 * @Desc: 模型提供者设置视图，按作用域（LLM / Embedding）分组
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelProviderSettingsView {

    /**
     * LLM 作用域的模型提供者设置
     */
    private ScopeSettingsView llm;

    /**
     * Embedding 作用域的模型提供者设置
     */
    private ScopeSettingsView embedding;
}
