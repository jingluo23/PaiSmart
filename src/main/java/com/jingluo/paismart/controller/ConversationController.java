package com.jingluo.paismart.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jingluo.paismart.domain.response.ResponseResult;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.service.ConversationService;
import com.jingluo.paismart.utils.JwtUtils;

/**
 * @Author: 鲸落
 * @Date: 2026/9/20 10:38
 * @Desc: 对话历史查询接口，支持按会话ID查询消息或按时间范围查询用户的对话记录
 */
@RestController
@RequestMapping("/api/v1/users/conversation")
public class ConversationController {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ConversationService conversationService;

    /**
     * 查询对话历史记录，支持两种模式：提供会话ID时查询指定会话的消息，否则按时间范围查询当前用户的对话
     *
     * @param token
     *            请求头中的JWT token，用于解析当前用户名
     * @param start_date
     *            开始时间，可选，支持精确到秒、分、小时及仅日期的格式
     * @param end_date
     *            结束时间，可选，与开始时间配合使用
     * @param conversationId
     *            逻辑会话ID，可选，提供时优先按会话ID查询
     * @return 消息历史列表
     */
    @GetMapping
    public ResponseResult<?> getConversations(@RequestHeader("Authorization") String token,
        @RequestParam(required = false) String start_date, @RequestParam(required = false) String end_date,
        @RequestParam(required = false) String conversationId) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (StringUtils.isBlank(username)) {
            throw new CustomException("无效的token", HttpStatus.UNAUTHORIZED);
        }

        List<Map<String, Object>> messages;

        if (StringUtils.isNotBlank(conversationId)) {
            messages = conversationService.getMessagesByConversationId(conversationId);
        } else {
            LocalDateTime startDateTime = parseStartDate(start_date);
            LocalDateTime endDateTime = parseEndDate(end_date);
            messages = conversationService
                .toMessageHistory(conversationService.getConversations(username, startDateTime, endDateTime), false);
        }

        return ResponseResult.success(messages);
    }

    /**
     * 解析结束时间字符串为 LocalDateTime，支持精确到秒、分、小时及仅日期的格式，仅提供日期时解析为当日 23:59:59
     *
     * @param dateTimeStr
     *            结束时间字符串
     * @return 解析后的结束时间，输入为空时返回 null
     */
    private LocalDateTime parseEndDate(String dateTimeStr) {
        if (StringUtils.isBlank(dateTimeStr) || dateTimeStr.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (DateTimeParseException e1) {
            try {
                if (dateTimeStr.length() == 16) {
                    return LocalDateTime.parse(dateTimeStr + ":59");
                }

                if (dateTimeStr.length() == 13) {
                    return LocalDateTime.parse(dateTimeStr + ":59:59");
                }

                if (dateTimeStr.length() == 10) {
                    return LocalDate.parse(dateTimeStr).plusDays(1).atStartOfDay().minusSeconds(1);
                }
            } catch (Exception e2) {
                throw new CustomException("无效的结束时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
            }
        }

        throw new CustomException("无效的结束时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
    }

    /**
     * 解析起始时间字符串为 LocalDateTime，支持精确到秒、分、小时及仅日期的格式，仅提供日期时解析为当日 00:00
     *
     * @param dateTimeStr
     *            起始时间字符串
     * @return 解析后的起始时间，输入为空时返回 null
     */
    private LocalDateTime parseStartDate(String dateTimeStr) {
        if (StringUtils.isBlank(dateTimeStr) || dateTimeStr.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (DateTimeParseException e1) {
            try {
                if (dateTimeStr.length() == 16) {
                    return LocalDateTime.parse(dateTimeStr + ":00");
                }

                if (dateTimeStr.length() == 13) {
                    return LocalDateTime.parse(dateTimeStr + ":00:00");
                }

                if (dateTimeStr.length() == 10) {
                    return LocalDate.parse(dateTimeStr).atStartOfDay();
                }
            } catch (Exception e2) {
                throw new CustomException("无效的起始时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
            }
        }

        throw new CustomException("无效的起始时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
    }
}
