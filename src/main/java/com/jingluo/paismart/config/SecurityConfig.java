package com.jingluo.paismart.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.jingluo.paismart.utils.ResponseResultWriter;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 17:14
 * @Desc: Spring Security 安全配置：定义各接口的认证/授权规则，
 *        采用无状态（STATELESS）会话策略，并串联 JWT 认证过滤器与组织标签授权过滤器
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private OrgTagAuthorizationFilter orgTagAuthorizationFilter;

    @Autowired
    private ResponseResultWriter responseResultWriter;

    /**
     * 构建安全过滤链：关闭 CSRF、按路径配置访问权限、启用无状态会话，
     * 过滤器顺序为 JWT 认证 -> 组织标签授权
     *
     * @param http HttpSecurity 构建器
     * @return 配置完成的安全过滤链
     * @throws Exception 配置过程出现异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        try {
            // 禁用CSRF保护
            http.csrf(csrf -> csrf.disable())
                // 配置请求的授权规则
                .authorizeHttpRequests(authorize -> authorize
                    // 允许静态资源访问
                    .requestMatchers("/", "/test.html", "/static/test.html", "/static/**", "/*.js", "/*.css", "/*.ico")
                    .permitAll()
                    // 允许 WebSocket 连接
                    .requestMatchers("/chat/**", "/ws/**").permitAll()
                    // 允许登录注册接口
                    .requestMatchers("/api/v1/users/register", "/api/v1/users/login").permitAll()
                    // 允许测试接口
                    .requestMatchers("/api/v1/test/**").permitAll()
                    // LiteParse OCR 回调接口：可通过 aliyun.ocr.callback-token 进行轻量校验
                    .requestMatchers("/api/v1/internal/ocr/**").permitAll()
                    // 文件上传和下载相关接口 - 普通用户和管理员都可访问
                    .requestMatchers("/api/v1/upload/**", "/api/v1/parse", "/api/v1/documents/download",
                        "/api/v1/documents/preview", "/api/v1/documents/page-preview")
                    .hasAnyRole("USER", "ADMIN")
                    // 对话历史相关接口 - 用户只能查看自己的历史，管理员可以查看所有
                    .requestMatchers("/api/v1/users/conversation/**").hasAnyRole("USER", "ADMIN")
                    // 搜索接口 - 普通用户和管理员都可访问
                    .requestMatchers("/api/search/**").hasAnyRole("USER", "ADMIN")
                    // 聊天相关接口 - WebSocket停止Token获取
                    .requestMatchers("/api/v1/chat/**").hasAnyRole("USER", "ADMIN")
                    // 管理员专属接口 - 知识库管理、系统状态、用户活动监控
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    // 用户组织标签管理接口
                    .requestMatchers("/api/v1/users/primary-org").hasAnyRole("USER", "ADMIN")
                    // 其他请求需要认证
                    .anyRequest().authenticated())
                // 配置会话管理策略
                // 设置会话创建策略为STATELESS，表示不会创建会话，通常用于无状态的API应用
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 配置认证/授权异常处理：与全局异常处理器保持一致，统一返回 ResponseResult 结构，
                // HTTP 状态保持 200，业务码由 body 携带，由前端按 code 统一判断成败
                .exceptionHandling(exception -> exception
                    // 未认证（无 Token 或 Token 已彻底失效）访问受保护接口
                    .authenticationEntryPoint((request, response, authException) -> responseResultWriter.write(response,
                        HttpStatus.UNAUTHORIZED.value(), "未认证或登录已失效，请重新登录"))
                    // 已认证但权限不足（如普通用户访问管理员接口）
                    .accessDeniedHandler((request, response, accessDeniedException) -> responseResultWriter.write(response,
                        HttpStatus.FORBIDDEN.value(), "无权限访问该资源")))
                // 添加JWT认证过滤器
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 添加组织标签授权过滤器
                .addFilterAfter(orgTagAuthorizationFilter, JwtAuthenticationFilter.class);

            // 记录安全配置加载成功的信息
            // 返回配置好的安全过滤链
            return http.build();
        } catch (Exception e) {
            // 记录配置安全过滤链失败的错误信息
            log.warn("配置安全过滤器链失败", e);

            // 抛出异常，以便外部处理
            throw e;
        }
    }
}
