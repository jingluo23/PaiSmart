package com.jingluo.paismart.controller;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jingluo.paismart.domain.response.ResponseResult;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.service.ConversationService;
import com.jingluo.paismart.utils.JwtUtils;

/**
 * @Author: 鲸落
 * @Date: 2026/9/20 11:00
 * @Desc: 对话会话管理接口，提供会话列表查询、创建、归档、切换当前会话及取消归档功能
 */
@RestController
@RequestMapping("/api/v1/users/conversations")
public class ConversationSessionController {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ConversationService conversationService;

    /**
     * 查询当前登录用户的会话列表，按最后更新时间倒序排列
     *
     * @param token
     *            Authorization 请求头中的 Bearer token，用于解析用户身份
     * @return 会话列表，每项包含会话ID、逻辑会话ID、标题、状态及创建/更新时间
     */
    @GetMapping
    public ResponseResult<?> listSessions(@RequestHeader("Authorization") String token) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (StringUtils.isBlank(username)) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }

        Long userId = Long.parseLong(jwtUtils.extractUserIdFromToken(token.replace("Bearer ", "")));
        List<Map<String, Object>> sessions = conversationService.getConversationSessions(userId);

        return ResponseResult.success(sessions);
    }

    /**
     * 为当前登录用户创建新会话，并将 Redis 中的当前会话指针指向新会话，后续消息将记录到新会话下
     *
     * @param token
     *            Authorization 请求头中的 Bearer token，用于解析用户身份
     * @return 新创建的会话信息，包含逻辑会话ID、标题、状态及创建/更新时间
     */
    @PostMapping
    public ResponseResult<?> createSession(@RequestHeader("Authorization") String token) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (StringUtils.isBlank(username)) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }

        Long userId = Long.parseLong(jwtUtils.extractUserIdFromToken(token.replace("Bearer ", "")));
        Map<String, Object> session = conversationService.createConversationSession(userId);

        return ResponseResult.success(session);
    }

    /**
     * 归档指定会话，归档后会话状态置为 ARCHIVED，不在默认会话列表中展示
     *
     * @param token
     *            Authorization 请求头中的 Bearer token，用于校验用户身份
     * @param conversationId
     *            逻辑会话ID，路径参数
     * @return 归档结果提示
     */
    @PutMapping("/{conversationId}/archive")
    public ResponseResult<?> archiveSession(@RequestHeader("Authorization") String token,
        @PathVariable String conversationId) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (username == null || username.isEmpty()) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }

        conversationService.archiveConversationSession(conversationId);

        return ResponseResult.success("归档成功");
    }

    /**
     * 切换当前登录用户的当前会话，后续消息将记录到目标会话下
     *
     * @param token
     *            Authorization 请求头中的 Bearer token，用于解析用户身份
     * @param conversationId
     *            目标逻辑会话ID，路径参数
     * @return 切换结果提示
     */
    @PutMapping("/{conversationId}/switch")
    public ResponseResult<?> switchSession(@RequestHeader("Authorization") String token,
        @PathVariable String conversationId) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (StringUtils.isBlank(username)) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }

        Long userId = Long.parseLong(jwtUtils.extractUserIdFromToken(token.replace("Bearer ", "")));

        conversationService.switchCurrentConversation(userId, conversationId);

        return ResponseResult.success("切换对话成功");
    }

    /**
     * 取消归档指定会话，会话状态恢复为 ACTIVE
     *
     * @param token
     *            Authorization 请求头中的 Bearer token，用于校验用户身份
     * @param conversationId
     *            逻辑会话ID，路径参数
     * @return 取消归档结果提示
     */
    @PutMapping("/{conversationId}/unarchive")
    public ResponseResult<?> unarchiveSession(@RequestHeader("Authorization") String token,
        @PathVariable String conversationId) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (username == null || username.isEmpty()) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }

        conversationService.unarchiveConversationSession(conversationId);

        return ResponseResult.success("取消归档成功");
    }
}
