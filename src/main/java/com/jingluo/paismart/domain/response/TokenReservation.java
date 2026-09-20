package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/18 17:45
 * @Desc: Token 预留记录：调用模型前按估算值预扣额度，调用完成后按实际用量多退少补， 失败时凭此记录回滚预扣部分
 */
@AllArgsConstructor
@Data
public class TokenReservation {

    /**
     * 配额作用域（如 embedding / embedding-query-global-minute）
     */
    private String scope;

    /**
     * 预留归属的用户标识，全局预算时固定为 global
     */
    private String userId;

    /**
     * Redis 配额计数键
     */
    private String quotaKey;

    /**
     * 请求次数指标键，空字符串表示该预留不参与请求次数计量
     */
    private String metricKey;

    /**
     * 预留的 Token 数
     */
    private long reservedTokens;

    /**
     * 该配额的上限值
     */
    private long limit;

    /**
     * 配额键的剩余过期秒数
     */
    private long expiresInSeconds;

    /**
     * 是否为空操作预留（未启用配额管理），结算与回滚时直接跳过
     */
    private boolean noop;

    /**
     * 结算后是否保留配额键用于历史统计（用户日配额需要跨天聚合查询）
     */
    private boolean retainHistory;

    /**
     * 构造空操作预留，用于用户不受配额管理或功能未启用的场景
     *
     * @param scope
     *            配额作用域
     * @param userId
     *            用户标识
     * @return 不产生任何实际扣减的预留记录
     */
    public static TokenReservation noop(String scope, String userId) {
        return new TokenReservation(scope, userId, "", "", 0, 0, 0, true, false);
    }
}
