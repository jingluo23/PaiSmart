package com.jingluo.paismart.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.enums.SessionStatusEnum;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.Conversation;
import com.jingluo.paismart.model.ConversationSession;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.ConversationRepository;
import com.jingluo.paismart.repository.ConversationSessionRepository;
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

    @Autowired
    private ConversationSessionRepository conversationSessionRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

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

    /**
     * 查询用户的会话列表，按最后更新时间倒序排列，标题为空的会话返回默认标题“新对话”
     *
     * @param userId
     *            用户ID
     * @return 会话列表，每项包含会话ID、逻辑会话ID、标题、状态及创建/更新时间
     */
    public List<Map<String, Object>> getConversationSessions(Long userId) {
        List<ConversationSession> sessions = conversationSessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (ConversationSession session : sessions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", session.getId());
            item.put("conversationId", session.getConversationId());
            item.put("title", StringUtils.isNotBlank(session.getTitle()) ? session.getTitle() : "新对话");
            item.put("status", session.getStatus().name());
            item.put("createdAt",
                Objects.nonNull(session.getCreatedAt()) ? session.getCreatedAt().format(TIMESTAMP_FORMATTER) : null);
            item.put("updatedAt",
                Objects.nonNull(session.getUpdatedAt()) ? session.getUpdatedAt().format(TIMESTAMP_FORMATTER) : null);

            result.add(item);
        }

        return result;
    }

    /**
     * 为用户创建新会话，生成 UUID 作为逻辑会话ID，并更新 Redis 中的当前会话指针 （key 为 user:{userId}:current_conversation，有效期 7 天），使后续消息自动记录到新会话下
     *
     * @param userId
     *            用户ID
     * @return 新创建的会话信息，包含逻辑会话ID、标题、状态及创建/更新时间
     */
    public Map<String, Object> createConversationSession(Long userId) {
        User user =
            userRepository.findById(userId).orElseThrow(() -> new CustomException("未找到用户", HttpStatus.NOT_FOUND));

        String conversationId = UUID.randomUUID().toString();

        ConversationSession session = new ConversationSession();
        session.setUser(user);
        session.setConversationId(conversationId);
        session.setTitle("新对话");
        session.setStatus(SessionStatusEnum.ACTIVE);
        conversationSessionRepository.save(session);

        // Update Redis so the backend uses this new conversation for subsequent messages
        String redisKey = "user:" + userId + ":current_conversation";
        redisTemplate.opsForValue().set(redisKey, conversationId, Duration.ofDays(7));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversationId);
        result.put("title", "新对话");
        result.put("status", "ACTIVE");
        result.put("createdAt", session.getCreatedAt().format(TIMESTAMP_FORMATTER));
        result.put("updatedAt", session.getUpdatedAt().format(TIMESTAMP_FORMATTER));

        return result;
    }

    /**
     * 归档指定会话，将会话状态置为 ARCHIVED，不在默认会话列表中展示
     *
     * @param conversationId
     *            逻辑会话ID
     */
    public void archiveConversationSession(String conversationId) {
        ConversationSession session = conversationSessionRepository.findByConversationId(conversationId)
            .orElseThrow(() -> new CustomException("对话不存在", HttpStatus.NOT_FOUND));
        session.setStatus(SessionStatusEnum.ARCHIVED);

        conversationSessionRepository.save(session);
    }

    /**
     * 切换用户的当前会话，校验目标会话存在后，将 Redis 中的当前会话指针更新为目标会话 （key 为 user:{userId}:current_conversation，有效期 7 天），后续消息将记录到该会话下
     *
     * @param userId
     *            用户ID
     * @param conversationId
     *            目标逻辑会话ID
     */
    public void switchCurrentConversation(Long userId, String conversationId) {
        if (!conversationSessionRepository.existsByConversationId(conversationId)) {
            throw new CustomException("对话不存在", HttpStatus.NOT_FOUND);
        }

        String redisKey = "user:" + userId + ":current_conversation";
        redisTemplate.opsForValue().set(redisKey, conversationId, Duration.ofDays(7));
    }

    /**
     * 取消归档指定会话，将会话状态恢复为 ACTIVE
     *
     * @param conversationId
     *            逻辑会话ID
     */
    public void unarchiveConversationSession(String conversationId) {
        ConversationSession session = conversationSessionRepository.findByConversationId(conversationId)
            .orElseThrow(() -> new CustomException("对话不存在", HttpStatus.NOT_FOUND));
        session.setStatus(SessionStatusEnum.ACTIVE);

        conversationSessionRepository.save(session);
    }
}
