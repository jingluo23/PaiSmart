package com.jingluo.paismart.config;

import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 11:09
 * @Desc: Elasticsearch 客户端配置，构建带基本认证与 TLS 信任策略的 ElasticsearchClient Bean
 */
@Configuration
public class EsConfig {

    /**
     * Elasticsearch 服务地址（主机名或 IP）
     */
    @Value("${elasticsearch.host}")
    private String host;

    /**
     * Elasticsearch 服务端口
     */
    @Value("${elasticsearch.port}")
    private int port;

    /**
     * 连接协议，支持 http / https，默认 https
     */
    @Value("${elasticsearch.scheme:https}")
    private String scheme;

    /**
     * 认证用户名，默认 elastic
     */
    @Value("${elasticsearch.username:elastic}")
    private String username;

    /**
     * 认证密码，默认 changeme
     */
    @Value("${elasticsearch.password:changeme}")
    private String password;

    /**
     * HTTPS 场景下是否信任所有证书（跳过证书与主机名校验），默认 true，仅建议开发环境开启
     */
    @Value("${elasticsearch.insecure-trust-all-certificates:true}")
    private boolean insecureTrustAllCertificates;

    /**
     * 构建 ElasticsearchClient Bean，组装顺序为：低级 RestClient（含认证与 TLS 配置）→ 传输层 → 高级客户端
     *
     * @return 可直接使用的 Elasticsearch 客户端实例
     */
    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // 创建低级客户端
        RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, scheme));

        // 设置基本认证
        if (StringUtils.isNotBlank(username)) {
            BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(httpClientBuilder -> {
                if ("https".equalsIgnoreCase(scheme) && insecureTrustAllCertificates) {
                    // 忽略 TLS 证书（仅限开发环境）
                    try {
                        SSLContext sslContext = SSLContexts.custom()
                            .loadTrustMaterial(null, (X509Certificate[] chain, String authType) -> true).build();
                        httpClientBuilder.setSSLContext(sslContext);
                        httpClientBuilder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                    } catch (Exception e) {
                        // ignore
                    }
                }
                return httpClientBuilder.setDefaultCredentialsProvider(credsProvider);
            });
        }

        RestClient restClient = builder.build();

        // 创建传输层
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());

        // 返回高级客户端
        return new ElasticsearchClient(transport);
    }
}
