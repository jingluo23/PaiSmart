package com.jingluo.paismart.service;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.config.RateLimitProperties;
import com.jingluo.paismart.exception.RateLimitExceededException;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 16:09
 * @Desc: 接口限流服务，基于 Redis 固定窗口计数，按 IP 对注册、登录等敏感操作限流
 */
@Service
public class RateLimitService {

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 校验指定 IP 的注册请求频率是否超限，超限时抛出限流异常
     *
     * @param ip
     *            客户端 IP
     */
    public void checkRegisterByIp(String ip) {
        checkSingleWindow("register:ip:" + ip, rateLimitProperties.getRegister().getMax(),
            rateLimitProperties.getRegister().getWindowSeconds(), "注册请求过于频繁");
    }

    /**
     * 单窗口限流检查
     * <p>
     * 以 key 做原子自增计数，首次计数时设置窗口过期时间；计数超过上限时抛出限流异常， 并携带窗口剩余秒数作为建议等待时间。
     *
     * @param key
     *            Redis 计数键（含业务前缀与客户端标识）
     * @param max
     *            窗口内允许的最大请求次数
     * @param windowSeconds
     *            窗口时长（秒）
     * @param message
     *            超限时的异常提示信息
     */
    private void checkSingleWindow(String key, long max, long windowSeconds, String message) {
        Long current = stringRedisTemplate.opsForValue().increment(key);
        if (Objects.isNull(current)) {
            return;
        }

        // 首次计数时为窗口设置过期时间
        if (current == 1) {
            stringRedisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
        }

        if (current > max) {
            // 读取窗口剩余时间作为建议等待秒数，读取失败时退化为整个窗口时长
            Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
            long retryAfterSeconds = Objects.isNull(ttl) || ttl < 0 ? windowSeconds : ttl;

            throw new RateLimitExceededException(message, retryAfterSeconds);
        }
    }

    /**
     * 校验指定 IP 的登录请求频率是否超限，超限时抛出限流异常
     *
     * @param ip
     *            客户端 IP
     */
    public void checkLoginByIp(String ip) {
        checkSingleWindow("login:ip:" + ip, rateLimitProperties.getLogin().getMax(),
            rateLimitProperties.getLogin().getWindowSeconds(), "登录请求过于频繁");
    }
}
