package com.jingluo.paismart.repository;

import java.util.List;

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
}
