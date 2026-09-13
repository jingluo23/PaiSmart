package com.jingluo.paismart.utils;

import java.util.Base64;
import java.util.Objects;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @author 鲸落
 * @date 2026/9/13 16:32
 * @Description JWT工具类
 */
@Slf4j
@Component
public class JwtUtils {

    /**
     * 这里存的是 Base64 编码后的密钥
     */
    @Value("${jwt.secret-key}")
    private String secretKeyBase64;

    /**
     * 从 JWT Token 中提取用户名
     *
     * @param token
     * @return
     */
    public String extractUsernameFromToken(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }

        try {
            Claims claims = extractClaimsIgnoreExpiration(token);

            return Objects.nonNull(claims) ? claims.getSubject() : null;
        } catch (Exception e) {
            log.warn("从token中提取用户名失败", e);
            return null;
        }
    }

    /**
     * 提取Claims，忽略过期异常
     *
     * @param token
     * @return
     */
    private Claims extractClaimsIgnoreExpiration(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }

        try {
            return Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(token).getBody();
        } catch (ExpiredJwtException e) {
            // 忽略过期异常，返回claims
            return e.getClaims();
        } catch (Exception e) {
            log.warn("从token中提取Claims失败", e);
            return null;
        }
    }

    /**
     * 解析 Base64 密钥，并返回 SecretKey
     * 
     * @return
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = Base64.getDecoder().decode(secretKeyBase64);

        return Keys.hmacShaKeyFor(keyBytes);
    }
}
