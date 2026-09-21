package com.jingluo.paismart.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.jingluo.paismart.domain.response.GenerationSnapshot;
import com.jingluo.paismart.domain.response.ReferenceInfo;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 14:27
 * @Desc: 聊天引用处理服务：维护 AI 生成任务与回答引用编号的映射， 支持从内存或 Redis 持久化的生成快照中查询引用详情
 */
@Slf4j
@Service
public class ChatHandler {

    @Autowired
    private ChatGenerationStateService chatGenerationStateService;

    /**
     * 用于存储每次生成任务的引用映射：generationId -> {referenceNumber -> detail}
     */
    private final Map<String, Map<Integer, ReferenceInfo>> generationReferenceMappings = new ConcurrentHashMap<>();

    /**
     * 查询指定生成任务中某个引用编号的详情： 内存映射未命中时回退到 Redis 中持久化的生成快照并重建映射
     *
     * @param generationId
     *            生成任务 ID
     * @param referenceNumber
     *            引用编号（回答文本中的上标序号）
     * @return 引用详情，任务或编号不存在时为 null
     */
    public ReferenceInfo getReferenceDetail(String generationId, int referenceNumber) {
        Map<Integer, ReferenceInfo> referenceMapping = generationReferenceMappings.get(generationId);
        if (CollectionUtils.isEmpty(referenceMapping)) {
            referenceMapping =
                chatGenerationStateService.getGeneration(generationId).map(GenerationSnapshot::getReferenceMappings)
                    .filter(mappings -> !CollectionUtils.isEmpty(mappings)).map(this::toReferenceInfoMap).orElse(null);
        }

        if (CollectionUtils.isEmpty(referenceMapping)) {
            return null;
        }

        ReferenceInfo detail = referenceMapping.get(referenceNumber);
        if (Objects.isNull(detail)) {
            return null;
        }

        return detail;
    }

    /**
     * 将 Redis 中序列化的引用映射（编号字符串 -> 字段 Map）还原为强类型结构， 数值字段按实际类型转换，无法解析的编号会被跳过
     *
     * @param serializedMappings
     *            序列化的引用映射
     * @return 强类型引用映射（编号 -> 引用详情）
     */
    private Map<Integer, ReferenceInfo> toReferenceInfoMap(Map<String, Map<String, Object>> serializedMappings) {
        Map<Integer, ReferenceInfo> referenceMap = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : serializedMappings.entrySet()) {
            Map<String, Object> item = entry.getValue();
            referenceMap.put(Integer.parseInt(entry.getKey()),
                new ReferenceInfo((String)item.get("fileMd5"), (String)item.get("fileName"),
                    item.get("pageNumber") instanceof Number number ? number.intValue() : null,
                    (String)item.get("anchorText"), (String)item.get("retrievalMode"),
                    (String)item.get("retrievalLabel"), (String)item.get("retrievalQuery"),
                    (String)item.get("matchedChunkText"), (String)item.get("evidenceSnippet"),
                    item.get("score") instanceof Number number ? number.doubleValue() : null,
                    item.get("chunkId") instanceof Number number ? number.intValue() : null));
        }

        return referenceMap;
    }
}
