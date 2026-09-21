package com.jingluo.paismart.domain.response;

import org.springframework.web.reactive.function.client.WebClient;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 摘要请求实际使用的 LLM 端点描述
 * <p>
 * 封装一次摘要调用所需的 WebClient、模型名与 Provider 名称。
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 17:37
 */
@AllArgsConstructor
@Data
public class SummaryEndpoint {

    /**
     * 已配置好 baseUrl 与鉴权头的 WebClient
     */
    private WebClient webClient;

    /**
     * 模型名称
     */
    private String model;

    /**
     * Provider 名称（如 deepseek 或运营配置的活动 Provider）
     */
    private String provider;
}
