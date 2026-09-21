package com.jingluo.paismart.utils;

import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.UserRepository;
import com.jingluo.paismart.service.TokenCacheService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.SignatureException;
import io.jsonwebtoken.security.Keys;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * @author 鲸落
 * @date 2026/9/13 16:32
 * @Description JWT工具类
 */
@Slf4j
@Component
public class JwtUtils {

    @Autowired
    private TokenCacheService tokenCacheService;

    @Autowired
    private UserRepository userRepository;

    /**
     * 这里存的是 Base64 编码后的密钥
     */
    @Value("${jwt.secret-key}")
    private String secretKeyBase64;

    /**
     * 1 hour (调整为1小时)
     */
    private static final long EXPIRATION_TIME = 3600000;

    /**
     * 7 days (refresh token有效期)
     */
    private static final long REFRESH_TOKEN_EXPIRATION_TIME = 604800000;

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

    /**
     * 验证刷新令牌是否有效
     * <p>
     * 校验流程：先从 JWT 中提取 refreshTokenId 并检查 Redis 缓存状态（支持服务端吊销）， 再验证 JWT 签名，最后确认令牌类型为 refresh，任一环节失败均返回 false。
     *
     * @param refreshToken
     *            刷新令牌
     * @return true 表示刷新令牌有效
     */
    public boolean validateRefreshToken(String refreshToken) {
        try {
            // 首先从JWT中提取refreshTokenId
            String refreshTokenId = extractRefreshTokenIdFromToken(refreshToken);
            if (StringUtils.isBlank(refreshTokenId)) {
                log.warn("刷新令牌中未包含refreshTokenId");

                return Boolean.FALSE;
            }

            // 检查Redis缓存中的refresh token状态
            if (!tokenCacheService.isRefreshTokenValid(refreshTokenId)) {
                log.warn("缓存中的刷新令牌无效: {}", refreshTokenId);

                return Boolean.FALSE;
            }

            // Redis验证通过，再验证JWT签名
            Claims claims =
                Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(refreshToken).getBody();

            // 验证是否为refresh token类型
            String tokenType = claims.get("type", String.class);
            if (!"refresh".equals(tokenType)) {
                log.warn("该令牌不是刷新令牌");

                return Boolean.FALSE;
            }

            return Boolean.TRUE;
        } catch (ExpiredJwtException e) {
            log.warn("刷新令牌已过期: {}", e.getClaims().get("refreshTokenId", String.class));
        } catch (SignatureException e) {
            log.warn("刷新令牌签名无效");
        } catch (Exception e) {
            log.warn("刷新令牌验证错误", e);
        }

        return Boolean.FALSE;
    }

    /**
     * 从刷新令牌中提取 refreshTokenId
     * <p>
     * 使用忽略过期异常的方式解析 Claims，即使刷新令牌已过期也能提取出 ID， 便于日志记录或过期处理逻辑使用。
     *
     * @param refreshToken
     *            刷新令牌
     * @return refreshTokenId，解析失败时返回 null
     */
    public String extractRefreshTokenIdFromToken(String refreshToken) {
        try {
            Claims claims = extractClaimsIgnoreExpiration(refreshToken);

            return Objects.nonNull(claims) ? claims.get("refreshTokenId", String.class) : null;
        } catch (Exception e) {
            log.warn("Error extracting refreshTokenId from token", e);

            return null;
        }
    }

    /**
     * 生成访问令牌（accessToken）
     * <p>
     * 根据用户名查询用户信息，签发携带 roleId、userId、组织标签等自定义 Claims 的 JWT， 有效期 1 小时，并将令牌信息缓存到 Redis 用于后续有效性校验。
     *
     * @param username
     *            用户名
     * @return 签名的访问令牌
     * @throws RuntimeException
     *             用户不存在时抛出
     */
    public String generateToken(String username) {
        SecretKey key = getSigningKey(); // 解析密钥

        // 获取用户信息
        User user = userRepository.findByUsername(username).orElseThrow(() -> new RuntimeException("未找到用户"));

        // 生成唯一的tokenId
        String tokenId = generateTokenId();

        long expireTime = System.currentTimeMillis() + EXPIRATION_TIME;

        // 创建token内容
        Map<String, Object> claims = new HashMap<>();
        claims.put("tokenId", tokenId); // 添加tokenId用于Redis缓存
        claims.put("role", user.getRole().name());
        claims.put("userId", user.getId().toString()); // 添加用户ID到JWT

        // 添加组织标签信息
        if (StringUtils.isNotBlank(user.getOrgTags())) {
            claims.put("orgTags", user.getOrgTags());
        }

        // 添加主组织标签信息
        if (StringUtils.isNotBlank(user.getPrimaryOrg())) {
            claims.put("primaryOrg", user.getPrimaryOrg());
        }

        String token = Jwts.builder().setClaims(claims).setSubject(username).setExpiration(new Date(expireTime))
            .signWith(key, SignatureAlgorithm.HS256).compact();

        // 缓存token信息到Redis
        tokenCacheService.cacheToken(tokenId, user.getId().toString(), username, expireTime);

        return token;
    }

    /**
     * 生成唯一的令牌 ID
     * <p>
     * 使用随机 UUID 并去除连字符，作为令牌在 Redis 缓存中的唯一标识。
     *
     * @return 32 位无连字符的 UUID 字符串
     */
    private String generateTokenId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 生成刷新令牌（refreshToken）
     * <p>
     * 签发携带 refreshTokenId 和 userId 的 JWT，并在 Claims 中标记 type=refresh 以区分访问令牌，有效期 7 天，同时将刷新令牌信息缓存到 Redis 用于有效性校验。
     *
     * @param username
     *            用户名
     * @return 签名的刷新令牌
     * @throws RuntimeException
     *             用户不存在时抛出
     */
    public String generateRefreshToken(String username) {
        SecretKey key = getSigningKey();

        // 获取用户信息
        User user = userRepository.findByUsername(username).orElseThrow(() -> new RuntimeException("未找到用户"));

        // 生成唯一的refreshTokenId
        String refreshTokenId = generateTokenId();
        long expireTime = System.currentTimeMillis() + REFRESH_TOKEN_EXPIRATION_TIME;

        // 创建refreshToken内容（相对简单，只包含基本信息）
        Map<String, Object> claims = new HashMap<>();
        // 添加refreshTokenId
        claims.put("refreshTokenId", refreshTokenId);
        claims.put("userId", user.getId().toString());
        // 标识这是一个refresh token
        claims.put("type", "refresh");

        String refreshToken = Jwts.builder().setClaims(claims).setSubject(username).setExpiration(new Date(expireTime))
            .signWith(key, SignatureAlgorithm.HS256).compact();

        // 缓存refresh token信息到Redis
        tokenCacheService.cacheRefreshToken(refreshTokenId, user.getId().toString(), null, expireTime);

        return refreshToken;
    }

    /**
     * 使单个令牌失效
     * <p>
     * 从令牌中提取 tokenId 与用户信息，先将令牌加入黑名单，再从有效令牌缓存及用户令牌集合中移除。 解析或吊销过程中的异常仅记录日志，不向外抛出。
     *
     * @param token
     *            待失效的 JWT 令牌
     */
    public void invalidateToken(String token) {
        try {
            String tokenId = extractTokenIdFromToken(token);
            if (StringUtils.isNotBlank(tokenId)) {
                Claims claims = extractClaimsIgnoreExpiration(token);
                if (Objects.nonNull(claims)) {
                    long expireTime = claims.getExpiration().getTime();
                    String userId = claims.get("userId", String.class);

                    // 加入黑名单
                    tokenCacheService.blacklistToken(tokenId, expireTime);

                    // 从缓存中移除
                    tokenCacheService.removeToken(tokenId, userId);
                }
            }
        } catch (Exception e) {
            log.error("令牌失效错误", e);
        }
    }

    /**
     * 从 JWT 令牌中提取 tokenId
     * <p>
     * 使用忽略过期异常的方式解析，即使令牌已过期也能提取出 ID。
     *
     * @param token
     *            JWT 令牌
     * @return 令牌 ID，解析失败时返回 null
     */
    public String extractTokenIdFromToken(String token) {
        try {
            Claims claims = extractClaimsIgnoreExpiration(token);
            return Objects.nonNull(claims) ? claims.get("tokenId", String.class) : null;
        } catch (Exception e) {
            log.debug("从令牌中提取令牌ID时出错", e);

            return null;
        }
    }

    /**
     * 从 JWT 令牌中提取用户 ID
     * <p>
     * 使用忽略过期异常的方式解析，即使令牌已过期也能提取出用户 ID。
     *
     * @param token
     *            JWT 令牌
     * @return 用户 ID，解析失败时返回 null
     */
    public String extractUserIdFromToken(String token) {
        try {
            Claims claims = extractClaimsIgnoreExpiration(token);

            return Objects.nonNull(claims) ? claims.get("userId", String.class) : null;
        } catch (Exception e) {
            log.error("从令牌中提取用户ID时出错: {}", token, e);

            return null;
        }
    }

    /**
     * 使用户的所有令牌失效
     * <p>
     * 委托缓存服务清空该用户持有的全部令牌记录，用于全端登出场景。
     *
     * @param userId
     *            用户 ID
     */
    public void invalidateAllUserTokens(String userId) {
        try {
            tokenCacheService.removeAllUserTokens(userId);
        } catch (Exception e) {
            log.warn("用户令牌全部失效错误: {}", userId, e);
        }
    }

    /**
     * 校验访问令牌：先查 Redis 缓存状态（快速失败），再验证 JWT 签名（双重验证）
     *
     * @param token
     * @return true 表示令牌有效
     */
    public boolean validateToken(String token) {
        try {
            // 首先从JWT中提取tokenId（快速失败）
            String tokenId = extractTokenIdFromToken(token);
            if (StringUtils.isBlank(tokenId)) {
                return Boolean.FALSE;
            }

            // 检查Redis缓存中的token状态
            if (!tokenCacheService.isTokenValid(tokenId)) {
                return Boolean.FALSE;
            }

            // Redis验证通过，再验证JWT签名（双重验证）
            Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(token);

            return Boolean.TRUE;
        } catch (ExpiredJwtException e) {
            log.warn("令牌已过期: {}", e.getClaims().get("tokenId", String.class));
        } catch (SignatureException e) {
            log.warn("无效的令牌签名");
        } catch (Exception e) {
            log.error("令牌验证错误", e);
        }

        return Boolean.FALSE;
    }

    /**
     * 从 Authorization 请求头中提取裸 token：剥离"Bearer "前缀后返回，
     * 不携带前缀时视为整体即为 token，空白内容返回 null
     *
     * @param authorization
     *            Authorization 请求头原始值
     * @return 裸 token，无法提取时为 null
     */
    public String extractBearerToken(String authorization) {
        if (StringUtils.isBlank(authorization)) {
            return null;
        }

        String trimmed = authorization.trim();
        if (trimmed.startsWith("Bearer ")) {
            return trimmed.substring(7);
        }

        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 从 token 中提取组织标签声明（忽略过期），解析失败时返回 null 而非抛出异常，
     * 供匿名降级场景（如公开文件访问）安全使用
     *
     * @param token
     *            JWT token
     * @return 组织标签字符串，不存在或解析失败时为 null
     */
    public String extractOrgTagsFromToken(String token) {
        try {
            Claims claims = extractClaimsIgnoreExpiration(token);

            return Objects.nonNull(claims) ? claims.get("orgTags", String.class) : null;
        } catch (Exception e) {
            log.warn("从令牌中提取组织标签时出错: {}", token, e);

            return null;
        }
    }
}
