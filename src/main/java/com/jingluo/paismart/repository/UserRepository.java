package com.jingluo.paismart.repository;


import com.jingluo.paismart.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @author 鲸落
 * @date 2026/9/13 17:01
 * @Description 用户接口
 */
public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * 根据用户名查询用户
     *
     * @param username
     * @return
     */
    Optional<User> findByUsername(String username);
}
