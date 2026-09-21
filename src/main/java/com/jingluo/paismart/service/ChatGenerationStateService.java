package com.jingluo.paismart.service;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingluo.paismart.domain.response.GenerationMeta;
import com.jingluo.paismart.domain.response.GenerationSnapshot;

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
}
