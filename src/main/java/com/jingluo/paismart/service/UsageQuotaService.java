package com.jingluo.paismart.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.jingluo.paismart.config.UsageQuotaProperties;
import com.jingluo.paismart.domain.response.DailyTokenQuota;
import com.jingluo.paismart.domain.response.DailyUsageAggregate;
import com.jingluo.paismart.domain.response.QuotaView;
import com.jingluo.paismart.domain.response.TokenReservation;
import com.jingluo.paismart.domain.response.TokenReservationBundle;
import com.jingluo.paismart.domain.response.UserUsageSnapshot;
import com.jingluo.paismart.exception.RateLimitExceededException;

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
     * 构建当日配额键
     *
     * @param scope
     *            配额作用域
     * @param userId
     *            用户标识
     * @return 当日配额计数键
     */
    private String buildQuotaKey(String scope, String userId) {
        return buildQuotaKey(scope, userId, currentDay());
    }

    /**
     * 构建指定日期的配额键
     *
     * @param scope
     *            配额作用域
     * @param userId
     *            用户标识
     * @param day
     *            日期字符串（ISO 格式）
     * @return 配额计数键
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

    /**
     * 预留 Embedding Token（用户配额 + 全局预算）：先扣用户当日配额， 再依次扣全局分钟、当日滚动预算；任一环节超限抛出限流异常并逆序回滚已扣部分
     *
     * @param userId
     *            请求者标识
     * @param texts
     *            待向量化的文本，用于估算预留量
     * @param budgetScope
     *            全局预算作用域（如 embedding-query）
     * @param minuteExceededMessage
     *            分钟预算超限提示
     * @param dayExceededMessage
     *            当日预算超限提示
     * @param minuteLimit
     *            全局分钟 Token 上限，非正值表示不启用
     * @param minuteWindowSeconds
     *            分钟窗口时长（秒）
     * @param dayLimit
     *            全局当日 Token 上限，非正值表示不启用
     * @param dayWindowSeconds
     *            当日窗口时长（秒）
     * @return 打包后的 Token 预留集合
     */
    public TokenReservationBundle reserveEmbeddingTokensWithGlobalBudget(String userId, List<String> texts,
        String budgetScope, String minuteExceededMessage, String dayExceededMessage, long minuteLimit,
        long minuteWindowSeconds, long dayLimit, long dayWindowSeconds) {
        int reserveTokens = Math.max(estimateEmbeddingTokens(texts), 1);

        List<TokenReservation> reservations = new ArrayList<>(3);
        try {
            addIfActive(reservations, reserveEmbeddingTokens(userId, texts));

            addIfActive(reservations, reserveGlobalRollingTokens(budgetScope, "minute", reserveTokens, minuteLimit,
                minuteWindowSeconds, minuteExceededMessage));

            addIfActive(reservations, reserveGlobalRollingTokens(budgetScope, "day", reserveTokens, dayLimit,
                dayWindowSeconds, dayExceededMessage));
        } catch (RuntimeException exception) {
            abortReservedTokens(reservations);

            throw exception;
        }

        return TokenReservationBundle.of(budgetScope, userId, reservations);
    }

    /**
     * 逆序回滚一组预留：与预留顺序相反地逐个退还，保证多级扣减的嵌套关系被对称还原
     *
     * @param reservations
     *            待回滚的预留列表
     */
    private void abortReservedTokens(List<TokenReservation> reservations) {
        if (CollectionUtils.isEmpty(reservations)) {
            return;
        }

        for (int index = reservations.size() - 1; index >= 0; index--) {
            abortReservation(reservations.get(index));
        }
    }

    /**
     * 回滚单个预留：将预扣的 Token 从配额计数中退还； 保留历史的键即使已被清空也会按原额退还并重设过期时间，避免统计口径缺失
     *
     * @param reservation
     *            待回滚的预留记录
     */
    public void abortReservation(TokenReservation reservation) {
        if (Objects.isNull(reservation) || reservation.isNoop()) {
            return;
        }

        if (!reservation.isRetainHistory()
            && !Boolean.TRUE.equals(stringRedisTemplate.hasKey(reservation.getQuotaKey()))) {
            return;
        }

        stringRedisTemplate.opsForValue().increment(reservation.getQuotaKey(), -reservation.getReservedTokens());
        if (reservation.isRetainHistory()) {
            ensureExpiry(reservation.getQuotaKey(), retentionTtlSeconds());
        }
    }

    /**
     * 全局滚动窗口预算预留：自增计数后与上限比较，超限则扣回本笔并抛出携带 重试等待时间的限流异常；首次写入时以窗口时长初始化过期时间
     *
     * @param scope
     *            预算作用域
     * @param windowLabel
     *            窗口标签（minute / day）
     * @param reserveTokens
     *            预留的 Token 数
     * @param limit
     *            窗口 Token 上限，非正值表示未启用
     * @param windowSeconds
     *            窗口时长（秒）
     * @param message
     *            超限提示信息
     * @return 全局预算预留记录（未启用时为 noop）
     */
    private TokenReservation reserveGlobalRollingTokens(String scope, String windowLabel, int reserveTokens, long limit,
        long windowSeconds, String message) {
        if (limit <= 0 || windowSeconds <= 0) {
            return TokenReservation.noop(scope, "global");
        }

        String quotaKey = buildGlobalBudgetKey(scope, windowLabel);
        Long total = stringRedisTemplate.opsForValue().increment(quotaKey, reserveTokens);
        if (Objects.nonNull(total) && total == reserveTokens) {
            stringRedisTemplate.expire(quotaKey, windowSeconds, TimeUnit.SECONDS);
        }

        Long ttl = stringRedisTemplate.getExpire(quotaKey, TimeUnit.SECONDS);
        long expiresInSeconds = Objects.isNull(ttl) || ttl < 0 ? windowSeconds : ttl;
        if (Objects.nonNull(total) && total > limit) {
            stringRedisTemplate.opsForValue().increment(quotaKey, -reserveTokens);
            throw new RateLimitExceededException(message, expiresInSeconds);
        }

        return new TokenReservation(scope + "-global-" + windowLabel, "global", quotaKey, "", reserveTokens, limit,
            expiresInSeconds, false, false);
    }

    /**
     * 构建全局滚动预算的 Redis 键
     *
     * @param scope
     *            预算作用域
     * @param windowLabel
     *            窗口标签（minute / day）
     * @return 全局预算计数键
     */
    private String buildGlobalBudgetKey(String scope, String windowLabel) {
        return "budget:" + scope + ":global:" + windowLabel;
    }

    /**
     * 预留用户当日 Embedding Token 配额：用户不受配额管理或功能未启用时返回 noop
     *
     * @param userId
     *            请求者标识
     * @param texts
     *            待向量化的文本，用于估算预留量
     * @return Token 预留记录
     */
    public TokenReservation reserveEmbeddingTokens(String userId, List<String> texts) {
        if (!isQuotaManaged(userId) || !properties.getEmbedding().isEnabled()) {
            return TokenReservation.noop("embedding", userId);
        }

        int estimatedTokens = estimateEmbeddingTokens(texts);

        return reserveDailyTokens("embedding", userId, Math.max(estimatedTokens, 1),
            properties.getEmbedding().getDayMaxTokens(), "Embedding当日Token额度已达上限");
    }

    /**
     * 预留用户当日 Token 配额：自增计数后与当日上限比较，超限则扣回本笔并抛出 限流异常（重试等待时间对齐到当天结束）；配额键按保留天数设置过期以支撑历史聚合
     *
     * @param scope
     *            配额作用域
     * @param userId
     *            用户标识
     * @param reserveTokens
     *            预留的 Token 数
     * @param dailyLimit
     *            当日 Token 上限
     * @param message
     *            超限提示信息
     * @return Token 预留记录
     */
    private TokenReservation reserveDailyTokens(String scope, String userId, int reserveTokens, long dailyLimit,
        String message) {
        String quotaKey = buildQuotaKey(scope, userId);
        long expiresInSeconds = secondsUntilEndOfDay();
        Long total = stringRedisTemplate.opsForValue().increment(quotaKey, reserveTokens);
        ensureExpiry(quotaKey, retentionTtlSeconds());

        if (Objects.nonNull(total) && total > dailyLimit) {
            stringRedisTemplate.opsForValue().increment(quotaKey, -reserveTokens);
            throw new RateLimitExceededException(message, expiresInSeconds);
        }

        return new TokenReservation(scope, userId, quotaKey, buildMetricKey(scope, userId), reserveTokens, dailyLimit,
            expiresInSeconds, false, true);
    }

    /**
     * 计算配额键的保留过期时间：到期时刻为保留期后一天的结束， 保证历史统计数据完整留存后再清理，最少保留一天
     *
     * @return 距过期的秒数
     */
    private long retentionTtlSeconds() {
        int retentionDays = Math.max(properties.getRetentionDays(), 1);

        LocalDateTime expireAt = LocalDate.now(ZoneId.systemDefault()).plusDays(retentionDays).atTime(LocalTime.MAX);

        return Math.max(Duration.between(LocalDateTime.now(ZoneId.systemDefault()), expireAt).getSeconds(), 86400);
    }

    /**
     * 为键设置过期时间：非法时长回退为一天，避免计数键永久残留
     *
     * @param key
     *            Redis 键
     * @param expiresInSeconds
     *            过期秒数
     */
    private void ensureExpiry(String key, long expiresInSeconds) {
        if (expiresInSeconds <= 0) {
            expiresInSeconds = 86400;
        }

        stringRedisTemplate.expire(key, expiresInSeconds, TimeUnit.SECONDS);
    }

    /**
     * 计算距离当日结束的剩余秒数，用于限流提示的重试等待时间；至少返回 1 秒
     *
     * @return 剩余秒数
     */
    private long secondsUntilEndOfDay() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime nextDay = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());

        return Math.max(Duration.between(now, nextDay).getSeconds(), 1);
    }

    /**
     * 收集有效预留：过滤掉 noop 预留，保证集合中只包含需要结算或回滚的记录
     *
     * @param reservations
     *            预留收集列表
     * @param reservation
     *            待判断的预留记录
     */
    private void addIfActive(List<TokenReservation> reservations, TokenReservation reservation) {
        if (Objects.nonNull(reservation) && !reservation.isNoop()) {
            reservations.add(reservation);
        }
    }

    /**
     * 回滚整个预留集合：调用失败时统一退还所有已预扣额度
     *
     * @param reservationBundle
     *            Token 预留集合
     */
    public void abortReservation(TokenReservationBundle reservationBundle) {
        if (Objects.isNull(reservationBundle) || reservationBundle.isNoop()) {
            return;
        }

        for (TokenReservation reservation : reservationBundle.getReservations()) {
            abortReservation(reservation);
        }
    }

    /**
     * 按实际用量结算整个预留集合：逐个预留多退少补并累计请求次数
     *
     * @param reservationBundle
     *            Token 预留集合
     * @param actualTokens
     *            本次调用实际消耗的 Token 数
     */
    public void settleReservation(TokenReservationBundle reservationBundle, int actualTokens) {
        if (Objects.isNull(reservationBundle) || reservationBundle.isNoop()) {
            return;
        }

        for (TokenReservation reservation : reservationBundle.getReservations()) {
            settleReservation(reservation, actualTokens);
        }
    }

    /**
     * 按实际用量结算单个预留：估算与实际差额为正则补扣、为负则退还； 计数键已过期时跳过补扣仅累计请求次数，实际用量超出上限时记录告警日志
     *
     * @param reservation
     *            Token 预留记录
     * @param actualTokens
     *            实际消耗的 Token 数
     */
    public void settleReservation(TokenReservation reservation, int actualTokens) {
        if (Objects.isNull(reservation) || reservation.isNoop()) {
            return;
        }

        long delta = (long)actualTokens - reservation.getReservedTokens();
        if (delta == 0) {

            incrementMetricIfPresent(reservation);

            return;
        }

        if (!reservation.isRetainHistory() && !stringRedisTemplate.hasKey(reservation.getQuotaKey())) {
            incrementMetricIfPresent(reservation);

            return;
        }

        Long total = stringRedisTemplate.opsForValue().increment(reservation.getQuotaKey(), delta);
        if (reservation.isRetainHistory()) {
            ensureExpiry(reservation.getQuotaKey(), retentionTtlSeconds());
        }
        incrementMetricIfPresent(reservation);

        if (Objects.nonNull(total) && total > reservation.getLimit()) {
            log.warn("用户 {} 的 {} token 实际用量超过额度: total={}, limit={}", reservation.getUserId(), reservation.getScope(),
                total, reservation.getLimit());
        }
    }

    /**
     * 结算时累计一次请求次数：仅对配置了指标键的预留生效
     *
     * @param reservation
     *            Token 预留记录
     */
    private void incrementMetricIfPresent(TokenReservation reservation) {
        if (StringUtils.isNotBlank(reservation.getMetricKey())) {
            incrementMetricKey(reservation.getMetricKey(), 1);
        }
    }

    /**
     * 累加指标计数并刷新其过期时间为保留时长，保证指标键始终有界存活
     *
     * @param metricKey
     *            指标计数键
     * @param increment
     *            累加值
     */
    private void incrementMetricKey(String metricKey, long increment) {
        stringRedisTemplate.opsForValue().increment(metricKey, increment);
        ensureExpiry(metricKey, retentionTtlSeconds());
    }
}
