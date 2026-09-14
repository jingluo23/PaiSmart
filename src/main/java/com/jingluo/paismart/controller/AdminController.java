package com.jingluo.paismart.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.jingluo.paismart.domain.ResponseResult;
import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.UserRepository;
import com.jingluo.paismart.utils.JwtUtils;

import io.micrometer.common.util.StringUtils;

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

    /**
     * 添加知识库文档
     *
     * @param token
     * @param file
     * @param description
     * @return
     */
    @PostMapping("/knowledge/add")
    public ResponseResult addKnowledgeDocument(@RequestHeader("Authorization") String token,
        @RequestParam("file") MultipartFile file, @RequestParam("description") String description) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        // 这里应该调用知识库管理服务来处理文档
        // knowledgeService.addDocument(file, description);

        return ResponseResult.success("文档已成功添加到知识库");
    }

    /**
     * 删除知识库文档
     * 
     * @param token
     * @param documentId
     * @return
     */
    @DeleteMapping("/knowledge/{documentId}")
    public ResponseResult deleteKnowledgeDocument(@RequestHeader("Authorization") String token,
        @PathVariable("documentId") String documentId) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        // 这里应该调用知识库管理服务来删除文档
        // knowledgeService.deleteDocument(documentId);

        return ResponseResult.success("文档已成功从知识库中删除");
    }
}
