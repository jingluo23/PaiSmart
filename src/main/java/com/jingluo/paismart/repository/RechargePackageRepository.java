package com.jingluo.paismart.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.model.RechargePackage;

/**
 * 充值套餐数据访问层
 *
 * @Author: 鲸落
 * @Date: 2026/9/16 11:32
 */
@Repository
public interface RechargePackageRepository extends JpaRepository<RechargePackage, Integer> {

    /**
     * 查询所有未删除的套餐，按排序字段升序排列
     */
    List<RechargePackage> findAllByDeletedFalseOrderBySortOrderAsc();

    /**
     * 查询指定价格之上所有已启用且未删除的套餐，按排序字段升序（用于用户端展示，price 传 1 表示过滤免费套餐）
     */
    List<RechargePackage> findAllByEnabledTrueAndDeletedFalseAndPackagePriceGreaterThanOrderBySortOrderAsc(Long price);

    /**
     * 根据价格查询已启用且未删除的套餐（自定义充值时按 1 分基准套餐折算 token）
     */
    Optional<RechargePackage> findByPackagePriceAndEnabledIsTrueAndDeletedFalse(Integer price);
}
