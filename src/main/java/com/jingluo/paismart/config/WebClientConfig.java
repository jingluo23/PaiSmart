package com.jingluo.paismart.config;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 17:22
 * @Desc: WebClient 响应式 HTTP 客户端配置，为向量化（Embedding）API 提供预配置的客户端实例
 */
@Configuration
public class WebClientConfig {

    /**
     * 向量化 API 的基础地址
     */
    @Value("${embedding.api.url}")
    private String apiUrl;

    /**
     * 向量化 API 的鉴权密钥
     */
    @Value("${embedding.api.key}")
    private String apiKey;

    /**
     * 构建向量化专用 WebClient：基础地址 + JSON 请求头 + 16MB 缓冲区（向量响应体较大），
     * 配置了密钥时附加 Bearer 鉴权头
     *
     * @return 向量化 API 专用 WebClient
     */
    @Bean
    public WebClient embeddingWebClient() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)) // 16MB
            .build();

        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl).exchangeStrategies(strategies)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        if (StringUtils.isNotBlank(apiKey)) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        return builder.build();
    }
}
