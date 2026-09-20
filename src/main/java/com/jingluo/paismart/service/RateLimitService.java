package com.jingluo.paismart.service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.config.RateLimitProperties;
import com.jingluo.paismart.domain.response.DualWindowLimitView;
import com.jingluo.paismart.domain.response.TokenBudgetView;
import com.jingluo.paismart.domain.response.TokenReservationBundle;
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

    @Autowired
    private RateLimitConfigService rateLimitConfigService;

    @Autowired
    private UsageQuotaService usageQuotaService;

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

    /**
     * 预留一次 Embedding 查询用量：先按分钟/当日双窗口校验请求频次， 再预留用户当日 Token 配额与全局分钟/当日 Token 预算
     *
     * @param userId
     *            请求者标识
     * @param texts
     *            待向量化的查询文本
     * @return 打包后的 Token 预留集合，超限时抛出限流异常
     */
    public TokenReservationBundle reserveEmbeddingQueryUsage(String userId, List<String> texts) {
        checkEmbeddingQueryByUser(userId);

        TokenBudgetView limit = rateLimitConfigService.getCurrentSettings().getEmbeddingQueryGlobalToken();

        return usageQuotaService.reserveEmbeddingTokensWithGlobalBudget(userId, texts, "embedding-query",
            "Embedding查询全网分钟Token预算已达上限", "Embedding查询全网当日Token预算已达上限", limit.getMinuteMax(),
            limit.getMinuteWindowSeconds(), limit.getDayMax(), limit.getDayWindowSeconds());
    }

    /**
     * 按分钟与当日双固定窗口校验单个用户的 Embedding 查询频次， 任一窗口超限抛出限流异常
     *
     * @param userId
     *            请求者标识
     */
    public void checkEmbeddingQueryByUser(String userId) {
        DualWindowLimitView limit = rateLimitConfigService.getCurrentSettings().getEmbeddingQueryRequest();

        checkSingleWindow("embedding:query:min:user:" + userId, limit.getMinuteMax(), limit.getMinuteWindowSeconds(),
            "Embedding查询过于频繁");

        checkSingleWindow("embedding:query:day:user:" + userId, limit.getDayMax(), limit.getDayWindowSeconds(),
            "Embedding查询当日次数已达上限");
    }

    /**
     * 预留一次 Embedding 上传用量：不做请求频次校验， 仅预留用户当日 Token 配额与全局分钟/当日 Token 预算
     *
     * @param userId
     *            请求者标识
     * @param texts
     *            待向量化的文档分块文本
     * @return 打包后的 Token 预留集合，超限时抛出限流异常
     */
    public TokenReservationBundle reserveEmbeddingUploadUsage(String userId, java.util.List<String> texts) {
        TokenBudgetView limit = rateLimitConfigService.getCurrentSettings().getEmbeddingUploadToken();

        return usageQuotaService.reserveEmbeddingTokensWithGlobalBudget(userId, texts, "embedding-upload",
            "Embedding上传全网分钟Token预算已达上限", "Embedding上传全网当日Token预算已达上限", limit.getMinuteMax(),
            limit.getMinuteWindowSeconds(), limit.getDayMax(), limit.getDayWindowSeconds());
    }
}
