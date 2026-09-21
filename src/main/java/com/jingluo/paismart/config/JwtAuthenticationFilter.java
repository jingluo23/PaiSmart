package com.jingluo.paismart.config;

import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.jingluo.paismart.service.CustomUserDetailsService;
import com.jingluo.paismart.utils.JwtUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 16:13
 * @Desc: JWT 认证过滤器，从请求头解析 Token 并完成用户认证；支持 Token 临期预刷新
 *        与过期后宽限期内刷新，新 Token 通过响应头 New-Token 返回给前端
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    /**
     * 每请求执行一次：解析 Token -> 校验/刷新 -> 加载用户信息 -> 写入安全上下文；
     * 认证异常时仅记录日志，不阻断后续过滤链（由后续授权规则决定放行或拒绝）
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        try {
            // 从请求头中提取 JWT Token
            String token = jwtUtils.extractToken(request);
            if (StringUtils.isNotBlank(token)) {
                String newToken = null;
                String username = null;

                // 首先检查token是否有效
                if (jwtUtils.validateToken(token)) {
                    // Token有效，检查是否需要预刷新
                    if (jwtUtils.shouldRefreshToken(token)) {
                        newToken = jwtUtils.refreshToken(token);
                    }
                    username = jwtUtils.extractUsernameFromToken(token);
                } else {
                    // Token无效/过期，检查是否在宽限期内可以刷新
                    if (jwtUtils.canRefreshExpiredToken(token)) {
                        newToken = jwtUtils.refreshToken(token);
                        if (StringUtils.isNotBlank(newToken)) {
                            username = jwtUtils.extractUsernameFromToken(newToken);
                        }
                    }
                }

                // 如果有新token，通过响应头返回给前端
                if (StringUtils.isNotBlank(newToken)) {
                    response.setHeader("New-Token", newToken);
                }

                // 设置用户认证信息
                if (StringUtils.isNotBlank(username)) {
                    UserDetails userDetails = customUserDetailsService.loadUserByUsername(username);

                    UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
            // 继续执行过滤链
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            // 记录错误日志
            log.warn("无法设置用户身份验证: {}", e);
        }
    }
}
