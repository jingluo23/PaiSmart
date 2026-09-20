package com.jingluo.paismart.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.Conversation;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.ConversationRepository;
import com.jingluo.paismart.repository.UserRepository;

import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 9:58
 * @Desc: 对话记录服务，负责对话历史的消息格式化转换及管理员侧的对话查询
 */
@Slf4j
@Service
public class ConversationService {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    /**
     * 对话时间戳的输出格式，精确到秒
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * 将对话记录转换为消息历史列表，每条对话拆分为 user 提问与 assistant 回答两条消息
     *
     * @param conversations
     *            对话记录列表，方法内部按时间戳和ID正序排序
     * @param includeUsername
     *            是否在消息中附带提问用户的用户名
     * @return 消息历史列表，每条消息包含角色、内容、时间戳、会话ID，可能包含引用映射和用户名
     */
    public List<Map<String, Object>> toMessageHistory(List<Conversation> conversations, boolean includeUsername) {
        List<Map<String, Object>> messages = new ArrayList<>();

        conversations.stream()
            .sorted(Comparator.comparing(Conversation::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Conversation::getId))
            .forEach(conversation -> {
                String timestamp = Objects.nonNull(conversation.getTimestamp())
                    ? conversation.getTimestamp().format(TIMESTAMP_FORMATTER) : null;

                String messageConversationId = StringUtils.isNotBlank(conversation.getConversationId())
                    ? conversation.getConversationId() : String.valueOf(conversation.getId());

                messages.add(buildMessage("user", conversation.getQuestion(), timestamp, messageConversationId, null,
                    includeUsername ? conversation.getUser().getUsername() : null));

                messages.add(buildMessage("assistant", conversation.getAnswer(), timestamp, messageConversationId,
                    parseReferenceMappings(conversation.getReferenceMappingsJson()),
                    includeUsername ? conversation.getUser().getUsername() : null));
            });

        return messages;
    }

    /**
     * 解析助手回复对应的引用映射 JSON
     *
     * @param referenceMappingsJson
     *            引用映射 JSON 字符串
     * @return 解析后的引用映射结构，JSON 为空或解析失败时返回 null
     */
    private Map<String, Map<String, Object>> parseReferenceMappings(String referenceMappingsJson) {
        if (StringUtils.isBlank(referenceMappingsJson)) {
            return null;
        }

        try {
            return objectMapper.readValue(referenceMappingsJson,
                new TypeReference<Map<String, Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("解析引用映射失败，将返回无引用详情的历史记录", e);

            return null;
        }
    }

    /**
     * 构建单条消息 Map，时间戳、会话ID、引用映射、用户名仅在非空时写入
     *
     * @param role
     *            消息角色，user 或 assistant
     * @param content
     *            消息内容
     * @param timestamp
     *            格式化后的时间戳，可为空
     * @param conversationId
     *            逻辑会话ID，可为空
     * @param referenceMappings
     *            引用映射，可为空
     * @param username
     *            用户名，可为空
     * @return 消息 Map
     */
    private Map<String, Object> buildMessage(String role, String content, String timestamp, String conversationId,
        Map<String, Map<String, Object>> referenceMappings, String username) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", role);
        message.put("content", content);

        if (StringUtils.isNotBlank(timestamp)) {
            message.put("timestamp", timestamp);
        }

        if (StringUtils.isNotBlank(conversationId)) {
            message.put("conversationId", conversationId);
        }

        if (!CollectionUtils.isEmpty(referenceMappings)) {
            message.put("referenceMappings", referenceMappings);
        }

        if (StringUtils.isNotBlank(username)) {
            message.put("username", username);
        }

        return message;
    }

    /**
     * 管理员查询对话记录，支持按目标用户和时间范围过滤，非管理员抛出禁止访问异常
     *
     * @param adminUsername
     *            管理员用户名，用于权限校验
     * @param targetUsername
     *            目标用户名，为空时查询所有用户的对话
     * @param startDate
     *            开始时间，与结束时间需同时提供
     * @param endDate
     *            结束时间，与开始时间需同时提供
     * @return 符合条件的对话记录列表，按时间正序排列
     */
    public List<Conversation> getAllConversations(String adminUsername, String targetUsername, LocalDateTime startDate,
        LocalDateTime endDate) {
        User admin = userRepository.findByUsername(adminUsername)
            .orElseThrow(() -> new CustomException("未找到管理员", HttpStatus.NOT_FOUND));

        if (admin.getRole() != Role.ADMIN) {
            throw new CustomException("未经授权的访问", HttpStatus.FORBIDDEN);
        }

        if (StringUtils.isNotBlank(targetUsername)) {
            User targetUser = userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> new CustomException("目标用户未找到", HttpStatus.NOT_FOUND));

            if (Objects.nonNull(startDate) && Objects.nonNull(endDate)) {
                return conversationRepository.findByUserIdAndTimestampBetweenOrderByTimestampAsc(targetUser.getId(),
                    startDate, endDate);
            } else {
                return conversationRepository.findByUserIdOrderByTimestampAsc(targetUser.getId());
            }
        } else {
            if (Objects.nonNull(startDate) && Objects.nonNull(endDate)) {
                return conversationRepository.findByTimestampBetweenOrderByTimestampAsc(startDate, endDate);
            } else {
                return conversationRepository.findAllByOrderByTimestampAsc();
            }
        }
    }

    /**
     * 按逻辑会话ID查询消息历史，按时间正序排列
     *
     * @param conversationId
     *            逻辑会话ID
     * @return 消息历史列表，每条消息包含角色、内容、时间戳、会话ID，可能包含引用映射
     */
    public List<Map<String, Object>> getMessagesByConversationId(String conversationId) {
        List<Conversation> conversations =
            conversationRepository.findByConversationIdOrderByTimestampAsc(conversationId);

        return toMessageHistory(conversations, false);
    }

    /**
     * 查询用户的对话记录，支持按时间范围过滤；用户名为 all 的管理员查询全部用户的对话，其余用户仅查询自己的对话
     *
     * @param username
     *            用户名
     * @param startDate
     *            开始时间，与结束时间需同时提供
     * @param endDate
     *            结束时间，与开始时间需同时提供
     * @return 符合条件的对话记录列表，按时间正序排列
     */
    public List<Conversation> getConversations(String username, LocalDateTime startDate, LocalDateTime endDate) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new CustomException("未找到用户", HttpStatus.NOT_FOUND));

        if (user.getRole() == Role.ADMIN && "all".equals(username)) {
            if (Objects.nonNull(startDate) && Objects.nonNull(endDate)) {
                return conversationRepository.findByTimestampBetweenOrderByTimestampAsc(startDate, endDate);
            } else {
                return conversationRepository.findAllByOrderByTimestampAsc();
            }
        } else {
            if (Objects.nonNull(startDate) && Objects.nonNull(endDate)) {
                return conversationRepository.findByUserIdAndTimestampBetweenOrderByTimestampAsc(user.getId(),
                    startDate, endDate);
            } else {
                return conversationRepository.findByUserIdOrderByTimestampAsc(user.getId());
            }
        }
    }
}
