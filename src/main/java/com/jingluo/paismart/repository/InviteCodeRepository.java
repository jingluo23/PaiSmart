package com.jingluo.paismart.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.model.InviteCode;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 15:47
 * @Desc: 邀请码数据访问层
 */
@Repository
public interface InviteCodeRepository extends JpaRepository<InviteCode, Long> {

    /**
     * 根据邀请码字符串查询邀请码
     *
     * @param code
     *            邀请码字符串
     * @return 匹配的邀请码
     */
    Optional<InviteCode> findByCode(String code);

    /**
     * 分页查询指定启用状态的邀请码
     *
     * @param enabled
     *            启用状态
     * @param pageable
     *            分页参数
     * @return 匹配的邀请码分页结果
     */
    Page<InviteCode> findByEnabled(Boolean enabled, Pageable pageable);
}
