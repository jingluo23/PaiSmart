package com.jingluo.paismart.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.jingluo.paismart.enums.OrderStatus;
import com.jingluo.paismart.model.RechargeOrder;

/**
 * 充值订单数据访问层
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 10:32
 */
@Repository
public interface RechargeOrderRepository extends JpaRepository<RechargeOrder, Long> {

    /**
     * 根据业务单号查询订单
     */
    Optional<RechargeOrder> findByTradeNo(String tradeNo);

    /**
     * 按订单状态查询指定用户的订单，按创建时间倒序
     */
    List<RechargeOrder> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, OrderStatus status);

    /**
     * 查询指定用户的全部订单，按创建时间倒序
     */
    List<RechargeOrder> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * 条件更新订单为支付成功（CAS）：仅当当前状态不是 SUCCEED 时生效， 返回受影响行数，1 表示本次调用获得 token 发放资格， 供支付回调与主动查单并发到达时避免重复发放
     */
    @Transactional
    @Modifying
    @Query("UPDATE RechargeOrder o SET o.status = :status, o.wxTransactionId = :wxTransactionId, o.payTime = :payTime, "
        + "o.updatedAt = :updatedAt WHERE o.tradeNo = :tradeNo AND o.status <> :status")
    int markSucceedIfNotAlready(@Param("tradeNo") String tradeNo, @Param("status") OrderStatus status,
        @Param("wxTransactionId") String wxTransactionId, @Param("payTime") LocalDateTime payTime,
        @Param("updatedAt") LocalDateTime updatedAt);
}
