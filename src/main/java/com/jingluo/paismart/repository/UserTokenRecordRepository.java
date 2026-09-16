package com.jingluo.paismart.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.model.UserTokenRecord;

/**
 * @Author: xuht32
 * @Date: 2026/9/15 18:07
 * @Desc: 用户 Token 变动记录数据访问接口
 */
@Repository
public interface UserTokenRecordRepository extends JpaRepository<UserTokenRecord, Long> {

    /**
     * 分页查询指定用户的 Token 变动记录
     *
     * @param userId
     *            用户 ID
     * @param pageable
     *            分页参数
     * @return 按记录日期倒序排列的变动记录分页结果
     */
    Page<UserTokenRecord> findByUserIdOrderByRecordDateDesc(String userId, Pageable pageable);
}
