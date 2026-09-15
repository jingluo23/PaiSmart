package com.jingluo.paismart.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingluo.paismart.model.InviteCode;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 15:47
 * @Desc: 邀请码数据访问层
 */
public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

    /**
     * 根据邀请码字符串查询邀请码
     *
     * @param code
     *            邀请码字符串
     * @return 匹配的邀请码
     */
    Optional<InviteCode> findByCode(String code);
}
