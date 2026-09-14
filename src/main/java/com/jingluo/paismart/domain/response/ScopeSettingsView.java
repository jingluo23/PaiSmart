package com.jingluo.paismart.domain.response;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 16:29
 * @Desc: 作用域维度的模型提供者设置视图（如 llm、embedding 作用域）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScopeSettingsView {

    /**
     * 作用域标识（llm / embedding）
     */
    private String scope;

    /**
     * 当前激活的模型提供者名称
     */
    private String activeProvider;

    /**
     * 该作用域下的模型提供者配置列表
     */
    private List<ProviderConfigView> providers;
}
