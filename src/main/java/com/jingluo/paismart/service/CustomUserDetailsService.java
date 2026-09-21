package com.jingluo.paismart.service;

import java.util.Collection;
import java.util.Collections;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.UserRepository;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 16:28
 * @Desc: Spring Security 用户加载服务，按用户名从数据库查询用户并转换为
 *        Security 框架所需的 UserDetails 对象
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    /**
     * 按用户名加载用户，未找到时抛出 UsernameNotFoundException
     *
     * @param username 用户名
     * @return 包含密码与角色权限的 UserDetails
     * @throws UsernameNotFoundException 用户不存在时抛出
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username).orElseThrow(() -> new UsernameNotFoundException("未找到用户"));

        // 返回 Spring Security 所需的 UserDetails 对象
        return new org.springframework.security.core.userdetails.User(user.getUsername(), user.getPassword(),
            // 获取用户的角色权限
            getAuthorities(user.getRole()));
    }

    /**
     * 将用户角色转换为 Spring Security 权限集合（前缀 ROLE_）
     *
     * @param role 用户角色
     * @return 单元素权限集合
     */
    private Collection<? extends GrantedAuthority> getAuthorities(Role role) {
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
