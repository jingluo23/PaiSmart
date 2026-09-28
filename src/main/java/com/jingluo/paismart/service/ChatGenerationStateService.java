package com.jingluo.paismart.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingluo.paismart.domain.response.GenerationMeta;
import com.jingluo.paismart.domain.response.GenerationSnapshot;
import com.jingluo.paismart.enums.GenerationStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * 聊天生成任务状态服务
 * <p>
 * 管理流式回答（generation）在 Redis 中的读写，每个任务拆分为三个 key：
 * meta（元数据）、content（回答内容）、refs（引用映射）。
 * 读取时聚合为 GenerationSnapshot 对外提供，支持用户归属校验与活动任务查询。
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 16:42
 */
@Slf4j
@Service
public class ChatGenerationStateService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 引用映射的反序列化类型：引用标记 -> 来源信息
     */
    private static final TypeReference<Map<String, Map<String, Object>>> REFERENCE_MAP_TYPE =
        new TypeReference<Map<String, Map<String, Object>>>() {};

    /**
     * 生成态数据在 Redis 中的统一过期时间（元数据、内容、引用映射与活动任务 key）
     */
    private static final Duration GENERATION_TTL = Duration.ofMinutes(30);

    /**
     * 查询指定用户的生成任务快照（带归属校验）
     *
     * @param generationId 生成任务 ID
     * @param userId       用户 ID
     * @return 任务快照；任务不存在或不属于该用户时为 empty
     */
    public Optional<GenerationSnapshot> getGenerationForUser(String generationId, String userId) {
        return getGeneration(generationId).filter(snapshot -> snapshot.getUserId().equals(userId));
    }

    /**
     * 查询生成任务快照（不做归属校验）
     *
     * @param generationId 生成任务 ID
     * @return 任务快照；任务不存在时为 empty
     */
    public Optional<GenerationSnapshot> getGeneration(String generationId) {
        GenerationMeta meta = readMeta(generationId);
        if (Objects.isNull(meta)) {
            return Optional.empty();
        }

        String content = Optional.ofNullable(redisTemplate.opsForValue().get(contentKey(generationId))).orElse("");
        Map<String, Map<String, Object>> references = readReferenceMappings(generationId);
        return Optional.of(toSnapshot(meta, content, references));
    }

    /**
     * 将 Redis 中的元数据、内容、引用映射聚合为快照对象
     *
     * @param meta              任务元数据
     * @param content           回答内容
     * @param referenceMappings 引用映射，为空时使用空 Map
     * @return 生成任务快照
     */
    private GenerationSnapshot toSnapshot(GenerationMeta meta, String content,
        Map<String, Map<String, Object>> referenceMappings) {
        return new GenerationSnapshot(meta.getGenerationId(), meta.getUserId(), meta.getConversationId(),
            meta.getQuestion(), meta.getStatus(), content, meta.getCreatedAt(), meta.getUpdatedAt(),
            meta.getErrorMessage(),
            CollectionUtils.isEmpty(referenceMappings) ? Collections.emptyMap() : referenceMappings);
    }

    /**
     * 读取任务的引用映射 JSON，缺失或解析失败时返回空 Map
     *
     * @param generationId 生成任务 ID
     * @return 引用映射
     */
    private Map<String, Map<String, Object>> readReferenceMappings(String generationId) {
        String raw = redisTemplate.opsForValue().get(referenceKey(generationId));
        if (StringUtils.isBlank(raw)) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(raw, REFERENCE_MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
     * 引用映射的 Redis key：chat:generation:{id}:refs
     *
     * @param generationId 生成任务 ID
     * @return Redis key
     */
    private String referenceKey(String generationId) {
        return "chat:generation:" + generationId + ":refs";
    }

    /**
     * 回答内容的 Redis key：chat:generation:{id}:content
     *
     * @param generationId 生成任务 ID
     * @return Redis key
     */
    private String contentKey(String generationId) {
        return "chat:generation:" + generationId + ":content";
    }

    /**
     * 读取任务元数据 JSON，缺失或解析失败时返回 null
     *
     * @param generationId 生成任务 ID
     * @return 任务元数据，可能为 null
     */
    private GenerationMeta readMeta(String generationId) {
        String raw = redisTemplate.opsForValue().get(metaKey(generationId));
        if (StringUtils.isBlank(raw)) {
            return null;
        }

        try {
            return objectMapper.readValue(raw, GenerationMeta.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 任务元数据的 Redis key：chat:generation:{id}:meta
     *
     * @param generationId 生成任务 ID
     * @return Redis key
     */
    private String metaKey(String generationId) {
        return "chat:generation:" + generationId + ":meta";
    }

    /**
     * 查询当前用户的活动生成任务（用于断线重连后恢复界面）
     *
     * @param userId 用户 ID
     * @return 活动任务快照；无活动任务时为 empty
     */
    public Optional<GenerationSnapshot> getActiveGenerationForUser(String userId) {
        String generationId = redisTemplate.opsForValue().get(activeGenerationKey(userId));
        if (StringUtils.isBlank(generationId)) {
            return Optional.empty();
        }

        return getGenerationForUser(generationId, userId);
    }

    /**
     * 用户活动任务的 Redis key：chat:user:{userId}:active_generation
     *
     * @param userId 用户 ID
     * @return Redis key
     */
    private String activeGenerationKey(String userId) {
        return "chat:user:" + userId + ":active_generation";
    }

    /**
     * 将任务标记为已取消（用户主动停止生成）
     *
     * @param generationId
     *            生成任务 ID
     */
    public void markCancelled(String generationId) {
        updateTerminalState(generationId, GenerationStatus.CANCELLED, null, null);
    }

    /**
     * 将任务写入终态（取消/失败/完成）：按需保存引用映射、更新元数据状态与结束时间， 并清除用户活动任务标记
     * <p>
     * 元数据不存在（已过期或非法 ID）时静默返回，不抛错。
     *
     * @param generationId
     *            生成任务 ID
     * @param status
     *            终态状态
     * @param errorMessage
     *            错误信息（仅失败态使用）
     * @param referenceMappings
     *            引用映射（完成态时保存；为空时仅刷新）
     */
    private void updateTerminalState(String generationId, GenerationStatus status, String errorMessage,
        Map<String, Map<String, Object>> referenceMappings) {
        GenerationMeta meta = readMeta(generationId);
        if (Objects.isNull(meta)) {
            return;
        }

        if (!CollectionUtils.isEmpty(referenceMappings)) {
            updateReferenceMappings(generationId, referenceMappings);
        } else {
            touch(generationId);
        }

        String now = LocalDateTime.now().toString();
        GenerationMeta updated = new GenerationMeta(meta.getGenerationId(), meta.getUserId(), meta.getConversationId(),
            meta.getQuestion(), status, meta.getCreatedAt(), now, errorMessage);

        writeMeta(updated);

        clearActiveGeneration(meta.getUserId(), generationId);
    }

    /**
     * 清除用户的活动任务标记：仅当 active key 仍指向本任务时才删除， 避免误删用户已开启的新任务
     *
     * @param userId
     *            用户 ID
     * @param generationId
     *            生成任务 ID
     */
    private void clearActiveGeneration(String userId, String generationId) {
        String current = redisTemplate.opsForValue().get(activeGenerationKey(userId));
        if (generationId.equals(current)) {
            redisTemplate.delete(activeGenerationKey(userId));
        }
    }

    /**
     * 保存（或清空）任务的引用映射并刷新任务活跃度； 保存失败只记日志，不中断生成主流程
     *
     * @param generationId
     *            生成任务 ID
     * @param referenceMappings
     *            引用映射；为空时删除对应 key
     */
    public void updateReferenceMappings(String generationId, Map<String, Map<String, Object>> referenceMappings) {
        try {
            if (referenceMappings == null || referenceMappings.isEmpty()) {
                redisTemplate.delete(referenceKey(generationId));
            } else {
                redisTemplate.opsForValue().set(referenceKey(generationId),
                    objectMapper.writeValueAsString(referenceMappings), GENERATION_TTL);
            }

            touch(generationId);
        } catch (Exception e) {
            log.warn("保存生成态引用映射失败: generationId={}", generationId, e);
        }
    }

    /**
     * 刷新任务活跃度：更新元数据的 updatedAt，续期内容与引用映射 key， 并刷新用户活动任务标记的 TTL
     *
     * @param generationId
     *            生成任务 ID
     */
    private void touch(String generationId) {
        GenerationMeta meta = readMeta(generationId);
        if (Objects.isNull(meta)) {
            return;
        }

        GenerationMeta updated =
            new GenerationMeta(meta.getGenerationId(), meta.getUserId(), meta.getConversationId(), meta.getQuestion(),
                meta.getStatus(), meta.getCreatedAt(), LocalDateTime.now().toString(), meta.getErrorMessage());

        writeMeta(updated);

        redisTemplate.expire(contentKey(generationId), GENERATION_TTL);

        redisTemplate.expire(referenceKey(generationId), GENERATION_TTL);

        redisTemplate.opsForValue().set(activeGenerationKey(meta.getUserId()), generationId, GENERATION_TTL);
    }

    /**
     * 将任务元数据写入 Redis（带 TTL），序列化失败时抛出 IllegalStateException
     *
     * @param meta
     *            任务元数据
     */
    private void writeMeta(GenerationMeta meta) {
        try {
            redisTemplate.opsForValue().set(metaKey(meta.getGenerationId()), objectMapper.writeValueAsString(meta),
                GENERATION_TTL);
        } catch (Exception e) {
            throw new IllegalStateException("保存生成态元数据失败", e);
        }
    }

    /**
     * 创建一个新的生成任务（状态 STREAMING）：初始化内容与元数据并发布用户活动任务标记
     *
     * @param userId
     *            用户 ID
     * @param conversationId
     *            会话 ID
     * @param question
     *            用户消息原文
     * @return 新任务的快照（初始内容为空）
     */
    public GenerationSnapshot createGeneration(String userId, String conversationId, String question) {
        String generationId = UUID.randomUUID().toString();
        String now = LocalDateTime.now().toString();
        GenerationMeta meta = new GenerationMeta(generationId, userId, conversationId, question,
            GenerationStatus.STREAMING, now, now, null);

        // 写入顺序：先把可读子项准备好，最后再发布 active key，避免读取者拿到 active key 后却查不到 meta/content。
        redisTemplate.delete(referenceKey(generationId));
        redisTemplate.opsForValue().set(contentKey(generationId), "", GENERATION_TTL);
        writeMeta(meta);
        redisTemplate.opsForValue().set(activeGenerationKey(userId), generationId, GENERATION_TTL);

        return toSnapshot(meta, "", Collections.emptyMap());
    }

    /**
     * 向任务内容追加一个流式文本块并刷新活跃度； 空块只刷新活跃度（维持 30 分钟 TTL），不追加内容
     *
     * @param generationId
     *            生成任务 ID
     * @param chunk
     *            文本块
     */
    public void appendChunk(String generationId, String chunk) {
        if (StringUtils.isBlank(chunk)) {
            touch(generationId);

            return;
        }

        redisTemplate.opsForValue().append(contentKey(generationId), chunk);
        touch(generationId);
    }

    /**
     * 将任务标记为失败并记录错误信息
     *
     * @param generationId
     *            生成任务 ID
     * @param errorMessage
     *            错误信息
     */
    public void markFailed(String generationId, String errorMessage) {
        updateTerminalState(generationId, GenerationStatus.FAILED, errorMessage, null);
    }

    /**
     * 将任务标记为完成并保存引用映射
     *
     * @param generationId
     *            生成任务 ID
     * @param referenceMappings
     *            引用映射（可为 null，表示无引用）
     */
    public void markCompleted(String generationId, Map<String, Map<String, Object>> referenceMappings) {
        updateTerminalState(generationId, GenerationStatus.COMPLETED, null, referenceMappings);
    }
}
