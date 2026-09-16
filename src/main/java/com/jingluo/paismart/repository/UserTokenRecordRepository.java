package com.jingluo.paismart.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.model.UserTokenRecord;

/**
 * @Author: xuht32
 * @Date: 2026/9/15 18:07
 * @Desc: 用户 Token 变动记录数据访问接口
 */
@Repository
public interface UserTokenRecordRepository extends JpaRepository<UserTokenRecord, Long> {}
