package com.jingluo.paismart.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.MinioClient;
import lombok.Getter;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 10:38
 * @Desc: MinIO 对象存储配置，注册 MinioClient 客户端及文件公网访问地址
 */
@Getter
@Configuration
public class MinioConfig {

    /**
     * MinIO 服务地址
     */
    @Value("${minio.endpoint}")
    private String endpoint;

    /**
     * 访问账号 AccessKey
     */
    @Value("${minio.accessKey}")
    private String accessKey;

    /**
     * 访问密钥 SecretKey
     */
    @Value("${minio.secretKey}")
    private String secretKey;

    /**
     * 文件对外访问的基础 URL，用于拼接生成文件访问链接
     */
    @Value("${minio.publicUrl}")
    private String publicUrl;

    /**
     * 构建 MinIO 客户端实例
     *
     * @return MinioClient 客户端
     */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    /**
     * 注册 MinIO 公网访问地址，供其他服务生成文件访问链接
     *
     * @return 公网访问地址
     */
    @Bean
    public String minioPublicUrl() {
        return publicUrl;
    }
}
