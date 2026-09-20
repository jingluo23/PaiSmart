package com.jingluo.paismart.domain.response;

import java.util.List;

import org.apache.commons.collections4.CollectionUtils;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/18 17:44
 * @Desc: Token 预留集合：将同一业务操作触发的多个预留（用户日配额、全局分钟/日预算）打包， 便于调用完成后统一结算或失败时统一回滚
 */
@AllArgsConstructor
@Data
public class TokenReservationBundle {

    /**
     * 业务作用域（如 embedding-query / embedding-upload）
     */
    private String scope;

    /**
     * 触发本次预留的用户标识
     */
    private String userId;

    /**
     * 本次操作产生的预留列表
     */
    private List<TokenReservation> reservations;

    /**
     * 是否为空操作预留（列表为空），结算与回滚时直接跳过
     */
    private boolean noop;

    /**
     * 工厂方法：规范化预留列表后构建集合，预留列表为空时标记为 noop
     *
     * @param scope
     *            业务作用域
     * @param userId
     *            用户标识
     * @param reservations
     *            预留列表
     * @return Token 预留集合
     */
    public static TokenReservationBundle of(String scope, String userId, List<TokenReservation> reservations) {
        List<TokenReservation> normalized =
            CollectionUtils.isEmpty(reservations) ? List.of() : List.copyOf(reservations);

        return new TokenReservationBundle(scope, userId, normalized, normalized.isEmpty());
    }
}
