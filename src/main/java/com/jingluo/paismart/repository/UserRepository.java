package com.jingluo.paismart.repository;


import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.model.User;

/**
 * @author 鲸落
 * @date 2026/9/13 17:01
 * @Description 用户接口
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * 根据用户名查询用户
     *
     * @param username
     * @return
     */
    Optional<User> findByUsername(String username);
}
