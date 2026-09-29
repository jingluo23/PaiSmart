package com.jingluo.paismart.repository;


import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.enums.Role;
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

    /**
     * 分页查询用户列表：关键词按用户名模糊匹配、角色精确匹配， 组织标签按 CSV 段精确匹配（覆盖首段/尾段/中段，避免 tagId 前缀误匹配）， 任一条件为 null 时跳过该过滤
     */
    @Query("SELECT u FROM User u WHERE (:keyword IS NULL OR u.username LIKE CONCAT('%', :keyword, '%')) "
        + "AND (:role IS NULL OR u.role = :role) "
        + "AND (:orgTag IS NULL OR u.orgTags = :orgTag OR u.orgTags LIKE CONCAT(:orgTag, ',%') "
        + "OR u.orgTags LIKE CONCAT('%,', :orgTag) OR u.orgTags LIKE CONCAT('%,', :orgTag, ',%'))")
    Page<User> findUserPage(@Param("keyword") String keyword, @Param("role") Role role, @Param("orgTag") String orgTag,
        Pageable pageable);
}
