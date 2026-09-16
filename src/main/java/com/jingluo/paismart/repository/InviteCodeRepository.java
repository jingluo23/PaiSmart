package com.jingluo.paismart.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.model.InviteCode;

import jakarta.persistence.LockModeType;

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

    /**
     * 按邀请码字符串加悲观写锁查询邀请码
     * <p>
     * 用于注册等并发消费场景，锁住记录直至事务结束，防止同一邀请码被并发超量使用。
     *
     * @param code
     *            邀请码字符串
     * @return 匹配的邀请码
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InviteCode i where i.code = :code")
    Optional<InviteCode> findByCodeForUpdate(@Param("code") String code);
}
