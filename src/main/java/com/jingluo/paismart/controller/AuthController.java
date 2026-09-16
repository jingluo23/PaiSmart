package com.jingluo.paismart.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jingluo.paismart.domain.request.RefreshTokenRequest;
import com.jingluo.paismart.domain.response.ResponseResult;
import com.jingluo.paismart.utils.JwtUtils;

import io.micrometer.common.util.StringUtils;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 14:55
 * @Desc: 认证控制器，负责令牌刷新等认证相关接口
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private JwtUtils jwtUtils;

    /**
     * 刷新访问令牌
     * <p>
     * 校验客户端上送的 refreshToken（先验证 Redis 缓存状态，再验证 JWT 签名）， 校验通过后签发全新的 accessToken 和 refreshToken 并一并返回。
     *
     * @param request
     *            包含 refreshToken 的请求体
     * @return 新的 token 和 refreshToken
     */
    @PostMapping("/refreshToken")
    public ResponseResult refreshToken(@RequestBody @Validated RefreshTokenRequest request) {
        // 验证refreshToken是否有效（这里我们用相同的验证逻辑）
        if (!jwtUtils.validateRefreshToken(request.getRefreshToken())) {
            return ResponseResult.fail(HttpStatus.UNAUTHORIZED.value(), "无效的刷新令牌");
        }

        // 从refreshToken中提取用户名
        String username = jwtUtils.extractUsernameFromToken(request.getRefreshToken());
        if (StringUtils.isBlank(username)) {
            return ResponseResult.fail(HttpStatus.UNAUTHORIZED.value(), "无法从刷新令牌中提取用户名");
        }

        // 生成新的token和refreshToken
        String newToken = jwtUtils.generateToken(username);

        String newRefreshToken = jwtUtils.generateRefreshToken(username);

        Map<String, String> map = Map.of("token", newToken, "refreshToken", newRefreshToken);

        return ResponseResult.success(map);
    }

    /**
     * 自定义后端错误响应
     * <p>
     * 将前端传入的错误码和错误信息直接封装为失败响应返回， 用于统一处理后端自定义错误场景。
     *
     * @param code
     *            错误码
     * @param msg
     *            错误信息
     * @return 携带错误码和错误信息的失败响应
     */
    @GetMapping("/error")
    public ResponseResult customBackendError(@RequestParam String code, @RequestParam String msg) {
        return ResponseResult.fail(Integer.parseInt(code), msg);
    }
}
