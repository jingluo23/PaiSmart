package com.jingluo.paismart.service;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.config.UsageQuotaProperties;
import com.jingluo.paismart.domain.response.DailyTokenQuota;
import com.jingluo.paismart.enums.ChangeType;
import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.enums.TokenType;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.model.UserTokenRecord;
import com.jingluo.paismart.repository.UserRepository;
import com.jingluo.paismart.repository.UserTokenRecordRepository;

import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 17:52
 * @Desc: 用户 Token 额度服务，基于 Redis 维护 LLM / Embedding 两类 Token 余额，并将每次变动落库为流水记录
 */
@Slf4j
@Service
public class UserTokenService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UsageQuotaProperties usageQuotaProperties;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserTokenRecordRepository userTokenRecordRepository;

    private static final String LLM_TOKEN_KEY_PREFIX = "user:token:llm:";

    private static final String EMBEDDING_TOKEN_KEY_PREFIX = "user:token:embedding:";

    /**
     * 为用户增加 LLM Token 额度，先记录流水再累加 Redis 余额
     *
     * @param userId
     *            用户 ID
     * @param tokens
     *            增加的 Token 数量（必须大于 0）
     * @param reason
     *            变动原因描述
     * @param remark
     *            备注信息（如操作管理员）
     */
    public void addLlmTokens(String userId, long tokens, String reason, String remark) {
        if (tokens <= 0) {
            throw new CustomException("增加的 Token 数量必须大于 0", HttpStatus.BAD_REQUEST);
        }

        String key = buildLlmTokenKey(userId);

        Long currentBalance = getLlmTokenBalance(userId);

        Long balanceAfter = safeAddTokenBalance(currentBalance, tokens);

        // 记录 Token 增加
        recordTokenIncrease(userId, TokenType.LLM, tokens, currentBalance, balanceAfter, reason, remark);

        stringRedisTemplate.opsForValue().increment(key, tokens);
    }

    /**
     * 安全累加 Token 余额，溢出时抛出业务异常
     *
     * @param currentBalance
     *            当前余额
     * @param tokens
     *            累加数量
     * @return 累加后的余额
     */
    private Long safeAddTokenBalance(Long currentBalance, long tokens) {
        try {
            return Math.addExact(currentBalance, tokens);
        } catch (ArithmeticException e) {
            throw new CustomException("Token 余额超过系统上限", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 查询用户 LLM Token 余额，余额键不存在时按配置初始化额度（管理员用户使用专属初始额度）并记录"注册赠送"流水
     *
     * @param userId
     *            用户 ID
     * @return 当前 LLM Token 余额，余额值无法解析时返回 0
     */
    public Long getLlmTokenBalance(String userId) {
        String key = buildLlmTokenKey(userId);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(value)) {
            long initToken = resolveInitToken(userId, usageQuotaProperties.getLlm());
            stringRedisTemplate.opsForValue().set(key, String.valueOf(initToken));
            // 记录 Token 增加
            recordTokenIncrease(userId, TokenType.LLM, initToken, 0L, initToken, "注册赠送", null);

            return initToken;
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("无法解析 LLM Token 余额：userId={}, value={}", userId, value);
            return 0L;
        }
    }

    /**
     * 记录一次 Token 增加流水，落库失败仅打印告警，不影响余额变更
     *
     * @param userId
     *            用户 ID
     * @param tokenType
     *            Token 类型
     * @param amount
     *            变动数量
     * @param balanceBefore
     *            变动前余额
     * @param balanceAfter
     *            变动后余额
     * @param reason
     *            变动原因描述
     * @param remark
     *            备注信息
     */
    private void recordTokenIncrease(String userId, TokenType tokenType, Long amount, Long balanceBefore,
        Long balanceAfter, String reason, String remark) {
        try {
            UserTokenRecord record = new UserTokenRecord(userId, LocalDate.now(), tokenType, ChangeType.INCREASE,
                amount, balanceBefore, balanceAfter, StringUtils.isBlank(reason) ? "" : reason,
                StringUtils.isBlank(remark) ? "" : remark, 0L);

            userTokenRecordRepository.save(record);
        } catch (Exception e) {
            log.warn("记录 Token 增加失败：userId={}, type={}, amount={}", userId, tokenType, amount, e);
        }
    }

    /**
     * 解析用户初始 Token 额度，管理员用户优先使用专属初始额度
     *
     * @param userId
     *            用户 ID
     * @param quota
     *            对应 Token 类型的额度配置
     * @return 初始 Token 额度
     */
    private long resolveInitToken(String userId, DailyTokenQuota quota) {
        long adminInitTokens = quota.getAdminInitTokens();
        if (adminInitTokens > 0 && isAdminUser(userId)) {
            return adminInitTokens;
        }

        return quota.getInitTokens();
    }

    /**
     * 判断指定用户是否为管理员，用户 ID 非法或查询失败时按普通用户处理
     *
     * @param userId
     *            用户 ID
     * @return 是否为管理员
     */
    private boolean isAdminUser(String userId) {
        if (StringUtils.isBlank(userId)) {
            return Boolean.FALSE;
        }
        try {
            return userRepository.findById(Long.parseLong(userId)).map(User::getRole).filter(Role.ADMIN::equals)
                .isPresent();
        } catch (NumberFormatException e) {
            log.warn("用户 ID 不是数字，跳过管理员初始额度判断: {}", userId);
            return Boolean.FALSE;
        }
    }

    /**
     * 构建 LLM Token 余额的 Redis 键
     *
     * @param userId
     *            用户 ID
     * @return Redis 键
     */
    private String buildLlmTokenKey(String userId) {
        return LLM_TOKEN_KEY_PREFIX + userId;
    }

    /**
     * 为用户增加 Embedding Token 额度，先记录流水再累加 Redis 余额
     *
     * @param userId
     *            用户 ID
     * @param tokens
     *            增加的 Token 数量（必须大于 0）
     * @param reason
     *            变动原因描述
     * @param remark
     *            备注信息（如操作管理员）
     */
    public void addEmbeddingTokens(String userId, long tokens, String reason, String remark) {
        if (tokens <= 0) {
            throw new CustomException("增加的 Token 数量必须大于 0", HttpStatus.BAD_REQUEST);
        }

        String key = buildEmbeddingTokenKey(userId);

        Long currentBalance = getEmbeddingTokenBalance(userId);

        Long balanceAfter = safeAddTokenBalance(currentBalance, tokens);
        // 记录 Token 增加
        recordTokenIncrease(userId, TokenType.EMBEDDING, tokens, currentBalance, balanceAfter, reason, remark);

        stringRedisTemplate.opsForValue().increment(key, tokens);
    }

    /**
     * 查询用户 Embedding Token 余额，余额键不存在时按配置初始化额度（管理员用户使用专属初始额度）并记录"注册赠送"流水
     *
     * @param userId
     *            用户 ID
     * @return 当前 Embedding Token 余额，余额值无法解析时返回 0
     */
    public Long getEmbeddingTokenBalance(String userId) {
        String key = buildEmbeddingTokenKey(userId);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(value)) {
            long initToken = resolveInitToken(userId, usageQuotaProperties.getEmbedding());
            stringRedisTemplate.opsForValue().set(key, String.valueOf(initToken));

            // 添加 Embedding Token 增加记录
            recordTokenIncrease(userId, TokenType.EMBEDDING, initToken, 0L, initToken, "注册赠送", null);

            return initToken;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("无法解析 Embedding Token 余额：userId={}, value={}", userId, value);

            return 0L;
        }
    }

    /**
     * 构建 Embedding Token 余额的 Redis 键
     *
     * @param userId
     *            用户 ID
     * @return Redis 键
     */
    private String buildEmbeddingTokenKey(String userId) {
        return EMBEDDING_TOKEN_KEY_PREFIX + userId;
    }
}
