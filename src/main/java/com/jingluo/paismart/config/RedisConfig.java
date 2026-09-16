package com.jingluo.paismart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 10:38
 * @Desc: Redis 配置，定义使用 JSON 序列化的 RedisTemplate
 */
@Configuration
public class RedisConfig {

    /**
     * 配置 RedisTemplate，key 使用字符串序列化，value 使用 JSON 序列化，便于直接存取对象
     *
     * @param connectionFactory
     *            Redis 连接工厂
     * @return RedisTemplate 实例
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
