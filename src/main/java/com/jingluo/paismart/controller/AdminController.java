package com.jingluo.paismart.controller;

import com.jingluo.paismart.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jingluo.paismart.domain.ResponseResult;
import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.utils.JwtUtils;

import io.micrometer.common.util.StringUtils;

import java.util.List;

/**
 * @author 鲸落
 * @date 2026/9/13 16:21
 * @Description 管理员控制器，提供管理知识库、查看系统状态和监控用户活动的接口
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    /**
     * 获取所有用户列表
     *
     * @param token
     * @return
     */
    @GetMapping("/users")
    public ResponseResult getAllUsers(@RequestHeader("Authorization") String token) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUserName = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUserName);

        List<User> users = userRepository.findAll();
        // 移除敏感信息
        users.forEach(user -> user.setPassword(null));

        return ResponseResult.success(users);
    }

    /**
     * 验证管理员
     *
     * @param adminUserName
     */
    private void validateAdmin(String adminUserName) {
        if (StringUtils.isBlank(adminUserName)) {
            throw new CustomException("token已失效，请重新登录", HttpStatus.UNAUTHORIZED);
        }

        User admin = userRepository.findByUsername(adminUserName)
            .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        if (Role.ADMIN != admin.getRole()) {
            throw new CustomException("无权限访问，需要管理员权限", HttpStatus.FORBIDDEN);
        }
    }
}
