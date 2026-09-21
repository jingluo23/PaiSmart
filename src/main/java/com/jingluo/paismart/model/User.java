package com.jingluo.paismart.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.jingluo.paismart.enums.Role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author 鲸落
 * @date 2026/9/13 16:43
 * @Description 用户类
 */
// JPA 要求实体必须有 public/protected 无参构造，因下方存在自定义构造函数需显式补上
@NoArgsConstructor
@Data
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class User {

    /**
     * 用户ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 用户名
     */
    @Column(nullable = false, unique = true)
    private String username;

    /**
     * 密码
     */
    @Column(nullable = false)
    private String password;

    /**
     * 角色
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /**
     * 用户所属组织标签，多个用逗号分隔
     */
    @Column(name = "org_tags")
    private String orgTags;

    /**
     * 用户主组织标签
     */
    @Column(name = "primary_org")
    private String primaryOrg;

    /**
     * 创建时间
     */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * 创建新用户的便捷构造函数，密码需在外部完成加密后传入
     *
     * @param username
     *            用户名
     * @param encode
     *            已加密的密码
     * @param role
     *            用户角色
     */
    public User(String username, String encode, Role role) {
        this.username = username;
        this.password = encode;
        this.role = role;
    }
}
