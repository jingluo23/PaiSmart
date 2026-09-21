package com.jingluo.paismart.config;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 17:05
 * @Desc: 生产环境配置校验器，仅在 prod profile 激活时生效：
 *        启动时检查数据库、JWT、DeepSeek、向量、MinIO、ES 等关键配置是否完整，
 *        并禁止生产环境开启不安全的 TLS 信任所有证书选项
 */
@Component
public class ProductionConfigValidator implements CommandLineRunner {

    private final Environment environment;

    public ProductionConfigValidator(Environment environment) {
        this.environment = environment;
    }

    /**
     * 应用启动时执行：非 prod 环境直接跳过；prod 环境逐项校验必需配置
     *
     * @param args 启动参数
     * @throws IllegalStateException 缺少必需配置或存在生产禁用项时抛出，阻止应用启动
     */
    @Override
    public void run(String... args) throws Exception {
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        if (!activeProfiles.contains("prod")) {
            return;
        }

        requireNonBlank("spring.datasource.url");
        requireNonBlank("spring.datasource.username");
        requireNonBlank("spring.datasource.password");
        requireNonBlank("jwt.secret-key");
        requireNonBlank("deepseek.api.url");
        requireNonBlank("deepseek.api.key");
        requireNonBlank("embedding.api.url");
        requireNonBlank("embedding.api.key");
        requireNonBlank("minio.endpoint");
        requireNonBlank("minio.accessKey");
        requireNonBlank("minio.secretKey");
        requireNonBlank("elasticsearch.host");
        requireNonBlank("elasticsearch.password");
        requireNonBlank("security.allowed-origins");

        String insecureTls = environment.getProperty("elasticsearch.insecure-trust-all-certificates", "false");
        if (Boolean.parseBoolean(insecureTls)) {
            throw new IllegalStateException(
                "Production profile forbids elasticsearch.insecure-trust-all-certificates=true");
        }
    }

    /**
     * 断言指定配置项存在且非空白，否则抛出异常终止启动
     *
     * @param key 配置项键名
     */
    private void requireNonBlank(String key) {
        String value = environment.getProperty(key);
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException("缺少必需的生产配置: " + key);
        }
    }
}
