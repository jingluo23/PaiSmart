package com.jingluo.paismart.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 15:35
 * @Desc: 令牌缓存服务，基于 Redis 维护 JWT 及刷新令牌的有效性状态
 */
@Slf4j
@Service
public class TokenCacheService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 刷新令牌在 Redis 中的 key 前缀
     */
    private static final String REFRESH_PREFIX = "jwt:refresh:";

    /**
     * 有效访问令牌在 Redis 中的 key 前缀
     */
    private static final String TOKEN_PREFIX = "jwt:valid:";

    /**
     * 用户持有令牌集合在 Redis 中的 key 前缀
     */
    private static final String USER_TOKENS_PREFIX = "jwt:user:";

    /**
     * 判断刷新令牌在 Redis 缓存中是否有效
     * <p>
     * 通过刷新令牌的唯一 ID 检查对应的 key 是否存在， 若 Redis 访问异常则保守地返回无效，拒绝刷新请求。
     *
     * @param refreshTokenId
     *            刷新令牌的唯一 ID
     * @return true 表示缓存中存在该刷新令牌
     */
    public boolean isRefreshTokenValid(String refreshTokenId) {
        try {
            String key = REFRESH_PREFIX + refreshTokenId;

            return redisTemplate.hasKey(key);
        } catch (Exception e) {
            log.warn("无法检查刷新令牌的有效性: {}", refreshTokenId, e);

            return false;
        }
    }

    /**
     * 缓存访问令牌信息
     * <p>
     * 将令牌 ID、用户 ID、用户名及过期时间以 Map 形式写入 Redis， 过期时间比 JWT 本身多 5 分钟缓冲，并同步登记到用户持有的令牌集合中。
     *
     * @param tokenId
     *            令牌唯一 ID
     * @param userId
     *            用户 ID
     * @param username
     *            用户名
     * @param expireTimeMs
     *            令牌过期时间戳（毫秒）
     */
    public void cacheToken(String tokenId, String userId, String username, long expireTimeMs) {
        try {
            String key = TOKEN_PREFIX + tokenId;
            Map<String, Object> tokenInfo = new HashMap<>();
            tokenInfo.put("userId", userId);
            tokenInfo.put("username", username);
            tokenInfo.put("expireTime", expireTimeMs);

            // 计算Redis过期时间（比JWT过期时间稍长一点）
            // 多5分钟缓冲
            long ttlSeconds = (expireTimeMs - System.currentTimeMillis()) / 1000 + 300;

            redisTemplate.opsForValue().set(key, tokenInfo, ttlSeconds, TimeUnit.SECONDS);

            // 同时添加到用户token集合中
            addTokenToUser(userId, tokenId, expireTimeMs);
        } catch (Exception e) {
            log.warn("无法缓存令牌: {}", tokenId, e);
        }
    }

    /**
     * 将令牌 ID 登记到用户持有的令牌集合
     * <p>
     * 以「用户 ID + :tokens」为 key 维护一个 Set，记录该用户当前所有的令牌 ID， 便于后续按用户维度管理（如强制下线、批量失效），集合过期时间与令牌保持一致。
     *
     * @param userId
     *            用户 ID
     * @param tokenId
     *            令牌唯一 ID
     * @param expireTimeMs
     *            令牌过期时间戳（毫秒）
     */
    private void addTokenToUser(String userId, String tokenId, long expireTimeMs) {
        try {
            String key = USER_TOKENS_PREFIX + userId + ":tokens";
            redisTemplate.opsForSet().add(key, tokenId);

            // 设置过期时间
            long ttlSeconds = (expireTimeMs - System.currentTimeMillis()) / 1000 + 300;
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("无法将令牌添加到用户集: {} - {}", userId, tokenId, e);
        }
    }

    /**
     * 缓存刷新令牌信息
     * <p>
     * 将刷新令牌 ID、用户 ID 及过期时间写入 Redis，TTL 与刷新令牌本身的有效期一致， 只有缓存中存在记录的刷新令牌才会被视为有效，用于支持服务端主动吊销。
     *
     * @param refreshTokenId
     *            刷新令牌唯一 ID
     * @param userId
     *            用户 ID
     * @param tokenId
     *            关联的访问令牌 ID（可为 null）
     * @param expireTimeMs
     *            刷新令牌过期时间戳（毫秒）
     */
    public void cacheRefreshToken(String refreshTokenId, String userId, String tokenId, long expireTimeMs) {
        try {
            String key = REFRESH_PREFIX + refreshTokenId;
            Map<String, Object> refreshInfo = new HashMap<>();
            refreshInfo.put("userId", userId);
            refreshInfo.put("tokenId", tokenId);
            refreshInfo.put("expireTime", expireTimeMs);

            long ttlSeconds = (expireTimeMs - System.currentTimeMillis()) / 1000;
            redisTemplate.opsForValue().set(key, refreshInfo, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("未能缓存刷新令牌: {}", refreshTokenId, e);
        }
    }
}
