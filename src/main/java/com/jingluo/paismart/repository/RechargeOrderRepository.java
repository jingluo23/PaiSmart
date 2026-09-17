package com.jingluo.paismart.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
