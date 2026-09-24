package com.jingluo.paismart.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.jingluo.paismart.domain.response.DailyUsagePoint;
import com.jingluo.paismart.domain.response.QuotaView;
import com.jingluo.paismart.domain.response.UsageAlert;
import com.jingluo.paismart.domain.response.UsageOverview;
import com.jingluo.paismart.domain.response.UsageRankingItem;
import com.jingluo.paismart.domain.response.UserUsageSnapshot;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.UserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 14:27
 * @Desc: 用量总览
 */
@Slf4j
@Service
public class UsageDashboardService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UsageQuotaService usageQuotaService;

    /**
     * 构建总览
     *
     * @param days
     * @return
     */
    public UsageOverview buildOverview(int days) {
        int normalizedDays = days <= 7 ? 7 : 30;

        List<User> users = userRepository.findAll();
        List<String> userIds =
            users.stream().filter(Objects::nonNull).map(user -> String.valueOf(user.getId())).toList();
        if (CollectionUtils.isEmpty(userIds)) {
            return null;
        }

        // fixme 如果用户较多，这里的 snapshots 的获取方案有oom的风险
        Map<String, UserUsageSnapshot> snapshots = usageQuotaService.getSnapshots(userIds);
        List<DailyUsagePoint> trends =
            usageQuotaService.getDailyAggregates(userIds, normalizedDays).stream().filter(Objects::nonNull)
                .map(item -> new DailyUsagePoint(item.getDay(), item.getChatRequestCount(), item.getLlmUsedTokens(),
                    item.getLlmRequestCount(), item.getEmbeddingUsedTokens(), item.getEmbeddingRequestCount()))
                .toList();

        DailyUsagePoint today =
            CollectionUtils.isEmpty(trends) ? new DailyUsagePoint("", 0, 0, 0, 0, 0) : trends.get(trends.size() - 1);

        List<UsageRankingItem> llmRankings = users.stream().filter(Objects::nonNull)
            .map(user -> toRankingItem(user, snapshots.get(String.valueOf(user.getId())), "llm"))
            .filter(item -> item.getUsedTokens() > 0)
            .sorted(Comparator.comparingLong(UsageRankingItem::getUsedTokens).reversed()).limit(5).toList();

        List<UsageRankingItem> embeddingRankings = users.stream().filter(Objects::nonNull)
            .map(user -> toRankingItem(user, snapshots.get(String.valueOf(user.getId())), "embedding"))
            .filter(item -> item.getUsedTokens() > 0)
            .sorted(Comparator.comparingLong(UsageRankingItem::getUsedTokens).reversed()).limit(5).toList();

        List<UsageAlert> alerts = users.stream().filter(Objects::nonNull)
            .flatMap(user -> buildAlerts(user, snapshots.get(String.valueOf(user.getId()))).stream())
            .sorted(Comparator.comparing((UsageAlert alert) -> "critical".equals(alert.getLevel()) ? 0 : 1)
                .thenComparing(UsageAlert::getUsageRatio).reversed())
            .toList();

        return new UsageOverview(normalizedDays, today, trends, llmRankings, embeddingRankings, alerts);
    }

    /**
     * 构建使用提醒
     * 
     * @param user
     * @param snapshot
     * @return
     */
    private List<UsageAlert> buildAlerts(User user, UserUsageSnapshot snapshot) {
        UserUsageSnapshot safeSnapshot = snapshot != null ? snapshot : emptySnapshot();
        List<UsageAlert> alerts = new ArrayList<>(2);

        UsageAlert llmAlert = buildAlert(user, "llm", safeSnapshot.getLlm());
        if (llmAlert != null) {
            alerts.add(llmAlert);
        }

        UsageAlert embeddingAlert = buildAlert(user, "embedding", safeSnapshot.getEmbedding());
        if (embeddingAlert != null) {
            alerts.add(embeddingAlert);
        }

        return alerts;
    }

    /**
     * 构建使用提醒
     * 
     * @param user
     * @param scope
     * @param quota
     * @return
     */
    private UsageAlert buildAlert(User user, String scope, QuotaView quota) {
        if (!quota.isEnabled() || quota.getLimitTokens() <= 0 || quota.getUsedTokens() <= 0) {
            return null;
        }

        double ratio = quota.getLimitTokens() == 0 ? 0d : (double)quota.getUsedTokens() / quota.getLimitTokens();
        if (quota.getRemainingTokens() == 0) {
            return new UsageAlert("critical", String.valueOf(user.getId()), user.getUsername(), scope,
                quota.getUsedTokens(), quota.getLimitTokens(), quota.getRemainingTokens(), quota.getRequestCount(),
                ratio, "今日额度已耗尽");
        }

        if (ratio >= 0.8d) {
            return new UsageAlert("warning", String.valueOf(user.getId()), user.getUsername(), scope,
                quota.getUsedTokens(), quota.getLimitTokens(), quota.getRemainingTokens(), quota.getRequestCount(),
                ratio, "今日额度已接近上限");
        }

        return null;
    }

    /**
     * 构建用户使用排名
     * 
     * @param user
     * @param snapshot
     * @param scope
     * @return
     */
    private UsageRankingItem toRankingItem(User user, UserUsageSnapshot snapshot, String scope) {
        UserUsageSnapshot safeSnapshot = snapshot != null ? snapshot : emptySnapshot();
        QuotaView quota = "embedding".equals(scope) ? safeSnapshot.getEmbedding() : safeSnapshot.getLlm();

        return new UsageRankingItem(String.valueOf(user.getId()), user.getUsername(), scope, quota.getUsedTokens(),
            quota.getLimitTokens(), quota.getRemainingTokens(), quota.getRequestCount());
    }

    /**
     * 构建空 snapshot
     * 
     * @return
     */
    private UserUsageSnapshot emptySnapshot() {
        return new UserUsageSnapshot("", 0, 0, 0, new QuotaView(false, 0, 0, 0, 0), new QuotaView(false, 0, 0, 0, 0));
    }
}
