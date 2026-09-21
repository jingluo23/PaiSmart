package com.jingluo.paismart.service;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.client.EmbeddingClient;
import com.jingluo.paismart.domain.response.EmbeddingUsageResult;
import com.jingluo.paismart.domain.response.VectorizationUsageResult;
import com.jingluo.paismart.entity.EsDocument;
import com.jingluo.paismart.entity.TextChunk;
import com.jingluo.paismart.enums.UsageType;
import com.jingluo.paismart.model.DocumentVector;
import com.jingluo.paismart.repository.DocumentVectorRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 9:24
 * @Desc: 向量化服务：读取文档分块，调用外部 Embedding 模型生成向量， 组装为 Elasticsearch 文档并批量写入知识库索引，同时统计实际用量
 */
@Slf4j
@Service
public class VectorizationService {

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    /**
     * 对指定文档执行向量化并返回实际用量：读取分块 -> 批量生成向量 -> 组装 ES 文档（含向量、页码、锚点与权限字段）-> 批量写入知识库索引
     *
     * @param fileMd5
     *            文件 MD5
     * @param userId
     *            文件归属用户 ID
     * @param orgTag
     *            文件组织标签
     * @param isPublic
     *            文件是否公开
     * @param requesterId
     *            发起请求的用户 ID（用于用量归属）
     * @return 实际用量（Tokens、分块数、模型版本）；无分块时返回零值用量
     */
    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag, boolean isPublic,
        String requesterId) {
        try {
            // 获取文件分块内容
            List<TextChunk> chunks = fetchTextChunks(fileMd5);
            if (CollectionUtils.isEmpty(chunks)) {
                return new VectorizationUsageResult(0, 0, embeddingClient.currentModelVersion());
            }

            // 提取文本内容
            List<String> texts = chunks.stream().map(TextChunk::getContent).toList();

            // 调用外部模型生成向量
            EmbeddingUsageResult embeddingResult = embeddingClient.embedWithUsage(texts, requesterId, UsageType.UPLOAD);
            List<float[]> vectors = embeddingResult.getVectors();

            // 构建 Elasticsearch 文档并存储
            List<EsDocument> esDocuments = IntStream.range(0, chunks.size())
                .mapToObj(i -> new EsDocument(UUID.randomUUID().toString(), fileMd5, chunks.get(i).getChunkId(),
                    chunks.get(i).getContent(), chunks.get(i).getPageNumber(), chunks.get(i).getAnchorText(),
                    vectors.get(i), embeddingResult.getModelVersion(), userId, orgTag, isPublic))
                .toList();

            // 批量存储到 Elasticsearch
            elasticsearchService.bulkIndex(esDocuments);

            return new VectorizationUsageResult(embeddingResult.getTotalTokens(), chunks.size(),
                embeddingResult.getModelVersion());
        } catch (Exception e) {
            String message = e.getMessage();
            if (StringUtils.isBlank(message)) {
                throw new RuntimeException("向量化失败", e);
            }

            throw new RuntimeException("向量化失败: " + message, e);
        }
    }

    /**
     * 按分块序号升序读取文档的所有文本分块
     *
     * @param fileMd5
     *            文件 MD5
     * @return 文本分块列表
     */
    private List<TextChunk> fetchTextChunks(String fileMd5) {
        // 调用 Repository 查询数据
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5);

        // 转换为 TextChunk 列表
        return vectors.stream().map(vector -> new TextChunk(vector.getChunkId(), vector.getTextContent(),
            vector.getPageNumber(), vector.getAnchorText())).toList();
    }

    /**
     * 向量化指定文档（不返回用量信息），供启动知识库初始化等不关心 token 用量的场景调用
     *
     * @param fileMd5
     *            文件 MD5
     * @param userId
     *            文件归属用户ID
     * @param orgTag
     *            归属组织标签
     * @param isPublic
     *            是否公开
     * @param requesterId
     *            发起向量化请求的用户ID（用于用量统计）
     */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId);
    }
}
