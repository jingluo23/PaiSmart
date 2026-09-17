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
     * ASCII 字符（英文、数字、半角符号）的 Token 折算比例
     */
    private static final double ASCII_TOKEN_RATIO = 0.30d;

    /**
     * CJK 字符（中文、日文假名、韩文）的 Token 折算比例
     */
    private static final double CJK_TOKEN_RATIO = 0.95d;

    /**
     * 其他 Unicode 字符的 Token 折算比例
     */
    private static final double OTHER_TOKEN_RATIO = 0.55d;

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

    /**
     * 估算一批文本的 Embedding Token 总数：单条文本按字符类别折算并附加每条固定开销， 总和再上浮 15% 作为安全余量
     *
     * @param texts
     *            待估算的文本列表
     * @return 估算的 Token 总数
     */
    public int estimateEmbeddingTokens(List<String> texts) {
        if (CollectionUtils.isEmpty(texts)) {
            return 0;
        }

        int total = 0;
        for (String text : texts) {
            total += estimateTextTokens(text) + 4;
        }

        return (int)Math.ceil(total * 1.15d);
    }

    /**
     * 估算单条文本的 Token 数：按字符类别（CJK/ASCII/其他）分别计数并乘以折算比例， 忽略空白字符，附加常数开销后向上取整
     *
     * @param text
     *            待估算文本
     * @return 估算的 Token 数，非空文本至少为 1
     */
    public int estimateTextTokens(String text) {
        if (StringUtils.isBlank(text)) {
            return 0;
        }

        int ascii = 0;
        int cjk = 0;
        int other = 0;

        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isWhitespace(current)) {
                continue;
            }

            Character.UnicodeScript script = Character.UnicodeScript.of(current);
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) {
                cjk++;
            } else if (current <= 0x7F) {
                ascii++;
            } else {
                other++;
            }
        }

        double estimated = ascii * ASCII_TOKEN_RATIO + cjk * CJK_TOKEN_RATIO + other * OTHER_TOKEN_RATIO + 12;
        return Math.max(1, (int)Math.ceil(estimated));
    }
}
