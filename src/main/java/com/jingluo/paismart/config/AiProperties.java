package com.jingluo.paismart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.jingluo.paismart.domain.response.Generation;
import com.jingluo.paismart.domain.response.Prompt;

import lombok.Data;

/**
 * AI 对话相关配置属性（配置前缀 ai.*）
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 17:32
 */
@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiProperties {

    /**
     * 提示词配置：规则文案、引用分隔符、无结果占位文案
     */
    private Prompt prompt = new Prompt();

    /**
     * 生成参数配置：采样温度、最大输出 tokens、top-p
     */
    private Generation generation = new Generation();
}
