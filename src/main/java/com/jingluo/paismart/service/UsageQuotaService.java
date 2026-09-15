package com.jingluo.paismart.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.jingluo.paismart.config.UsageQuotaProperties;
import com.jingluo.paismart.domain.response.DailyTokenQuota;
import com.jingluo.paismart.domain.response.DailyUsageAggregate;
import com.jingluo.paismart.domain.response.QuotaView;
import com.jingluo.paismart.domain.response.UserUsageSnapshot;

import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 14:45
 * @Desc: 用量配额服务
 */
@Service
@Slf4j
public class UsageQuotaService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UsageQuotaProperties properties;

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * 获取用户使用快照
     *
     * @param userIds
     * @return
     */
    public Map<String, UserUsageSnapshot> getSnapshots(List<String> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            return Collections.emptyMap();
        }

        Map<String, UserUsageSnapshot> result = new LinkedHashMap<>();
        for (String userId : userIds) {
            result.put(userId,
                new UserUsageSnapshot(currentDay(), readCounter(buildMetricKey("chat", userId)),
                    buildQuotaView("llm", userId, properties.getLlm()),
                    buildQuotaView("embedding", userId, properties.getEmbedding())));
        }

        return result;
    }

    /**
     * 构建额度视图
     *
     * @param scope
     * @param userId
     * @param quota
     * @return
     */
    private QuotaView buildQuotaView(String scope, String userId, DailyTokenQuota quota) {
        if (!isQuotaManaged(userId) || !quota.isEnabled()) {
            return new QuotaView(false, 0, 0, 0, 0);
        }

        long usedTokens = readCounter(buildQuotaKey(scope, userId));
        long requestCount = readCounter(buildMetricKey(scope, userId));
        long limitTokens = quota.getDayMaxTokens();
        long remainingTokens = Math.max(limitTokens - usedTokens, 0);

        return new QuotaView(true, usedTokens, limitTokens, remainingTokens, requestCount);
    }

    /**
     * 构建指标键
     *
     * @param scope
     * @param userId
     * @return
     */
    private String buildQuotaKey(String scope, String userId) {
        return buildQuotaKey(scope, userId, currentDay());
    }

    /**
     * 构建指标键
     *
     * @param scope
     * @param userId
     * @param day
     * @return
     */
    private String buildQuotaKey(String scope, String userId, String day) {
        return "quota:" + scope + ":" + day + ":user:" + userId;
    }

    /**
     * 判断是否是管理用户
     *
     * @param userId
     * @return
     */
    private boolean isQuotaManaged(String userId) {
        return StringUtils.isNotBlank(userId) && !userId.startsWith("system");
    }

    /**
     * 构建指标键
     *
     * @param scope
     * @param userId
     * @param day
     * @return
     */
    private String buildMetricKey(String scope, String userId, String day) {
        return "quota:" + scope + ":requests:" + day + ":user:" + userId;
    }

    /**
     * 构建指标键
     * 
     * @param scope
     * @param userId
     * @return
     */
    private String buildMetricKey(String scope, String userId) {
        return buildMetricKey(scope, userId, currentDay());
    }

    /**
     * 读取计数器
     *
     * @param key
     * @return
     */
    private long readCounter(String key) {
        if (StringUtils.isBlank(key)) {
            return 0L;
        }

        String value = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(value)) {
            return 0L;
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("无法解析 usage counter: key={}, value={}", key, value);

            return 0L;
        }
    }

    /**
     * 获取当前日期
     * 
     * @return
     */
    private String currentDay() {
        return ZonedDateTime.now(ZoneId.systemDefault()).format(DAY_FORMATTER);
    }

    /**
     * 获取日使用统计
     * 
     * @param userIds
     * @param days
     * @return
     */
    public List<DailyUsageAggregate> getDailyAggregates(List<String> userIds, int days) {
        if (CollectionUtils.isEmpty(userIds)) {
            return Collections.emptyList();
        }

        int normalizedDays = Math.max(1, Math.min(days, properties.getRetentionDays()));
        LocalDate today = LocalDate.now(ZoneId.systemDefault());

        return IntStream.range(0, normalizedDays).mapToObj(offset -> today.minusDays(normalizedDays - 1L - offset))
            .map(day -> buildDailyAggregate(userIds, day)).toList();
    }

    /**
     * 构建日使用统计
     * 
     * @param userIds
     * @param day
     * @return
     */
    private DailyUsageAggregate buildDailyAggregate(List<String> userIds, LocalDate day) {
        String dayString = day.format(DAY_FORMATTER);
        long chatRequestCount = 0L;
        long llmUsedTokens = 0L;
        long llmRequestCount = 0L;
        long embeddingUsedTokens = 0L;
        long embeddingRequestCount = 0L;

        for (String userId : userIds) {
            if (!isQuotaManaged(userId)) {
                continue;
            }

            chatRequestCount += readCounter(buildMetricKey("chat", userId, dayString));
            llmUsedTokens += readCounter(buildQuotaKey("llm", userId, dayString));
            llmRequestCount += readCounter(buildMetricKey("llm", userId, dayString));
            embeddingUsedTokens += readCounter(buildQuotaKey("embedding", userId, dayString));
            embeddingRequestCount += readCounter(buildMetricKey("embedding", userId, dayString));
        }

        return new DailyUsageAggregate(dayString, chatRequestCount, llmUsedTokens, llmRequestCount, embeddingUsedTokens,
            embeddingRequestCount);
    }

    /**
     * 获取单个用户的使用快照，无记录时返回全零的空快照
     *
     * @param userId
     * @return
     */
    public UserUsageSnapshot getSnapshot(String userId) {
        Map<String, UserUsageSnapshot> snapshots = getSnapshots(List.of(userId));

        return snapshots.getOrDefault(userId, emptySnapshot());
    }

    /**
     * 构建全零的空使用快照，用于用户无任何用量记录时的兜底返回
     *
     * @return
     */
    private UserUsageSnapshot emptySnapshot() {
        return new UserUsageSnapshot(currentDay(), 0, new QuotaView(false, 0, 0, 0, 0),
            new QuotaView(false, 0, 0, 0, 0));
    }
}
