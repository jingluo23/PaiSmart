package com.jingluo.paismart.service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.tika.exception.TikaException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jingluo.paismart.config.KafkaConfig;
import com.jingluo.paismart.domain.response.InMemoryPdfPreviewCache;
import com.jingluo.paismart.domain.response.PdfSinglePagePreview;
import com.jingluo.paismart.domain.response.VectorizationUsageResult;
import com.jingluo.paismart.enums.FileProcessingTaskEnum;
import com.jingluo.paismart.enums.FileUploadStatusEnum;
import com.jingluo.paismart.enums.FileUploadVectorizationStatusEnum;
import com.jingluo.paismart.model.FileProcessingTask;
import com.jingluo.paismart.model.FileUpload;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.ChunkInfoRepository;
import com.jingluo.paismart.repository.DocumentVectorRepository;
import com.jingluo.paismart.repository.FileUploadRepository;
import com.jingluo.paismart.repository.UserRepository;

import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 9:01
 * @Desc: 文档管理服务，负责文档删除、索引重建、向量化重试与状态回写、 可访问文件查询（含历史数据回填与去重）、下载链接生成及 PDF 单页预览（两级缓存）
 */
@Slf4j
@Service
public class DocumentService {

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private ChunkInfoRepository chunkInfoRepository;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private ParseService parseService;

    @Autowired
    private VectorizationService vectorizationService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaConfig kafkaConfig;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private UserRepository userRepository;

    /**
     * PDF 单页预览的本地一级缓存（cacheKey -> 缓存条目），优先于 Redis 命中
     */
    private static final Map<String, InMemoryPdfPreviewCache> PDF_SINGLE_PAGE_LOCAL_CACHE = new ConcurrentHashMap<>();

    /**
     * PDF 单页预览的 Redis 缓存 key 前缀，完整 key 为 前缀 + fileMd5 + ":" + pageNumber
     */
    private static final String PDF_SINGLE_PAGE_CACHE_PREFIX = "preview:pdf:single-page:";

    /**
     * 历史数据回填提示：已向量化但未记录实际用量
     */
    private static final String LEGACY_COMPLETED_WITHOUT_USAGE_MESSAGE = "历史数据未统计实际 Tokens，可按需重试以回写实际向量化结果";

    /**
     * 历史数据回填提示：向量记录缺失，视为向量化失败
     */
    private static final String LEGACY_FAILED_MESSAGE = "历史向量化结果缺失，可点击重试向量化重新处理";

    /**
     * PDF 单页预览缓存有效期（分钟），本地与 Redis 两级缓存共用
     */
    private static final long PDF_SINGLE_PAGE_CACHE_TTL_MINUTES = 30;

    private static final long PDF_SINGLE_PAGE_CACHE_TTL_MILLIS =
        TimeUnit.MINUTES.toMillis(PDF_SINGLE_PAGE_CACHE_TTL_MINUTES);

    /**
     * 删除文档：按依赖顺序清理 Elasticsearch 索引、MinIO 文件、文档向量、 分片元数据与上传记录，并失效该文件的 PDF 单页预览缓存； 单个存储组件删除失败仅记录日志不中断整体流程
     *
     * @param fileMd5
     *            文件 MD5
     * @param userId
     *            操作用户 ID（须为文件所有者）
     */
    @Transactional
    public void deleteDocument(String fileMd5, String userId) {
        try {
            // 获取文件信息以获取文件名
            FileUpload fileUpload =
                fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                    .orElseThrow(() -> new RuntimeException("文件不存在"));

            // 1. 删除Elasticsearch中的数据
            try {
                elasticsearchService.deleteByFileMd5(fileMd5);
            } catch (Exception e) {
                log.warn("从Elasticsearch删除文档时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }

            // 2. 删除MinIO中的文件（使用MD5作为对象路径）
            try {
                String objectName = "merged/" + fileUpload.getFileMd5();
                minioClient.removeObject(RemoveObjectArgs.builder().bucket("uploads").object(objectName).build());
            } catch (Exception e) {
                log.warn("使用MD5路径删除文件失败，尝试使用文件名路径: {}", fileMd5);

                // 降级：尝试使用旧的文件名路径（兼容旧数据）
                try {
                    String oldObjectName = "merged/" + fileUpload.getFileName();
                    minioClient
                        .removeObject(RemoveObjectArgs.builder().bucket("uploads").object(oldObjectName).build());
                } catch (Exception ex) {
                    log.warn("从MinIO删除文件时出错（新旧路径都失败）: {}", fileMd5, ex);
                    // 继续删除其他数据
                }
            }

            invalidatePdfSinglePagePreviewCache(fileMd5);

            // 3. 删除DocumentVector记录
            try {
                documentVectorRepository.deleteByFileMd5(fileMd5);
            } catch (Exception e) {
                log.warn("删除文档向量记录时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }

            // 删除分片元数据，避免同 MD5 文件再次上传时误判分片已经存在
            try {
                chunkInfoRepository.deleteByFileMd5(fileMd5);
            } catch (Exception e) {
                log.warn("删除文档分片元数据时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }

            // 4. 删除FileUpload记录
            fileUploadRepository.deleteByFileMd5(fileMd5);
        } catch (Exception e) {
            log.warn("删除文档过程中发生错误: {}", fileMd5, e);

            throw new RuntimeException("删除文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 失效指定文件的全部 PDF 单页预览缓存（本地内存 + Redis 按前缀匹配删除）
     *
     * @param fileMd5
     *            文件 MD5
     */
    private void invalidatePdfSinglePagePreviewCache(String fileMd5) {
        try {
            PDF_SINGLE_PAGE_LOCAL_CACHE.keySet()
                .removeIf(key -> key.startsWith(PDF_SINGLE_PAGE_CACHE_PREFIX + fileMd5 + ":"));

            Set<String> cacheKeys = stringRedisTemplate.keys(PDF_SINGLE_PAGE_CACHE_PREFIX + fileMd5 + ":*");
            if (CollectionUtils.isNotEmpty(cacheKeys)) {
                stringRedisTemplate.delete(cacheKeys);
            }
        } catch (Exception e) {
            log.warn("删除 PDF 单页预览缓存失败: fileMd5={}, error={}", fileMd5, e.getMessage());
        }
    }

    /**
     * 同步重建文档索引：清理旧的 ES 索引与向量记录后，重新解析文件并执行向量化， 全程实时执行并将实际用量回写到上传记录；任一环节失败则标记向量化失败
     *
     * @param fileMd5
     *            文件 MD5
     * @param requesterId
     *            发起重建的用户 ID（用于向量化用量归属）
     * @return 实际向量化用量（Tokens、分块数、模型版本）
     */
    @Transactional
    public VectorizationUsageResult reindexDocument(String fileMd5, String requesterId) {
        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
            .orElseThrow(() -> new RuntimeException("文件不存在"));

        markVectorizationProcessing(fileUpload, true);

        try (InputStream fileStream = uploadService.getMergedFileStream(fileMd5)) {
            // 1. 清理旧的 Elasticsearch 索引数据（失败不阻断，重建时会覆盖写入）
            try {
                elasticsearchService.deleteByFileMd5(fileMd5);
            } catch (Exception e) {
                log.warn("重建前清理 Elasticsearch 失败: fileMd5={}, error={}", fileMd5, e.getMessage());
            }

            // 2. 清理旧的向量记录与单页预览缓存
            documentVectorRepository.deleteByFileMd5(fileMd5);
            invalidatePdfSinglePagePreviewCache(fileMd5);

            // 3. 重新解析文件生成分块
            parseService.parseAndSave(fileMd5, fileStream, fileUpload.getUserId(), fileUpload.getOrgTag(),
                fileUpload.isPublic());

            // 4. 重新向量化并回写实际用量
            VectorizationUsageResult result = vectorizationService.vectorizeWithUsage(fileMd5, fileUpload.getUserId(),
                fileUpload.getOrgTag(), fileUpload.isPublic(), requesterId);

            markVectorizationCompleted(fileUpload, result);

            return result;
        } catch (TikaException e) {
            markVectorizationFailed(fileUpload, e);

            throw new RuntimeException("重建文档索引失败: " + e.getMessage(), e);
        } catch (Exception e) {
            markVectorizationFailed(fileUpload, e);

            throw new RuntimeException("重建文档索引失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将上传记录标记为向量化失败，错误信息取异常链中最有语义的一条
     *
     * @param fileUpload
     *            上传记录
     * @param error
     *            触发失败的异常
     */
    private void markVectorizationFailed(FileUpload fileUpload, Throwable error) {
        markVectorizationFailed(fileUpload, resolveVectorizationErrorMessage(error));
    }

    /**
     * 从异常链中解析用户可读的向量化错误信息：优先返回包含"余额不足"的那条； 其余情况取链上最深处的非空消息，无可用消息时返回兜底文案。 避免将底层技术细节（如空消息的包装异常）直接暴露给前端
     *
     * @param error
     *            异常
     * @return 面向用户的错误信息
     */
    private String resolveVectorizationErrorMessage(Throwable error) {
        Throwable current = error;
        String deepestMessage = null;

        while (Objects.nonNull(current)) {
            String message = current.getMessage();
            if (StringUtils.isNotBlank(message)) {
                deepestMessage = message;
                if (message.contains("余额不足")) {
                    return message;
                }
            }

            current = current.getCause();
        }

        if (StringUtils.isNotBlank(deepestMessage)) {
            return "向量化失败，请稍后重试";
        }

        if ("向量化失败".equals(deepestMessage) || "Error processing task".equals(deepestMessage)) {
            return "向量化失败，请稍后重试";
        }

        return deepestMessage;
    }

    /**
     * 将上传记录标记为向量化失败并持久化
     *
     * @param fileUpload
     *            上传记录
     * @param errorMessage
     *            用户可读的错误信息
     */
    private void markVectorizationFailed(FileUpload fileUpload, String errorMessage) {
        fileUpload.setVectorizationStatus(
            FileUploadVectorizationStatusEnum.VECTORIZATION_STATUS_FAILED.getVectorizationStatus());
        fileUpload.setVectorizationErrorMessage(trimVectorizationErrorMessage(errorMessage));

        fileUploadRepository.save(fileUpload);
    }

    /**
     * 裁剪错误信息以适配数据库字段长度（超过 1000 字符截断），空白信息替换为兜底文案
     *
     * @param errorMessage
     *            原始错误信息
     * @return 裁剪后的错误信息
     */
    private String trimVectorizationErrorMessage(String errorMessage) {
        if (StringUtils.isBlank(errorMessage)) {
            return "向量化失败，请稍后重试";
        }

        return errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage;
    }

    /**
     * 将上传记录标记为向量化完成，回写实际 Tokens 与分块数并清空错误信息
     *
     * @param fileUpload
     *            上传记录
     * @param result
     *            实际向量化用量
     */
    private void markVectorizationCompleted(FileUpload fileUpload, VectorizationUsageResult result) {
        fileUpload.setActualEmbeddingTokens((long)result.getActualEmbeddingTokens());
        fileUpload.setActualChunkCount(result.getActualChunkCount());
        fileUpload.setVectorizationStatus(
            FileUploadVectorizationStatusEnum.VECTORIZATION_STATUS_COMPLETED.getVectorizationStatus());
        fileUpload.setVectorizationErrorMessage(null);

        fileUploadRepository.save(fileUpload);
    }

    /**
     * 将上传记录标记为向量化处理中；可选清空历史实际用量，确保重试结果不残留旧数据
     *
     * @param fileUpload
     *            上传记录
     * @param resetActualUsage
     *            是否重置实际 Tokens/分块数为 null
     */
    private void markVectorizationProcessing(FileUpload fileUpload, boolean resetActualUsage) {
        fileUpload.setVectorizationStatus(
            FileUploadVectorizationStatusEnum.VECTORIZATION_STATUS_PROCESSING.getVectorizationStatus());
        fileUpload.setVectorizationErrorMessage(null);

        if (resetActualUsage) {
            fileUpload.setActualEmbeddingTokens(null);
            fileUpload.setActualChunkCount(null);
        }

        fileUploadRepository.save(fileUpload);
    }

    /**
     * 异步重试向量化：将状态置为处理中后，把重建任务投递到 Kafka 文件处理主题， 由消费端异步执行解析与向量化，本方法立即返回受理后的上传记录
     *
     * @param fileMd5
     *            文件 MD5
     * @param requesterId
     *            发起重试的用户 ID
     * @return 已更新为处理中状态的上传记录
     */
    @Transactional
    public FileUpload enqueueAsyncVectorizationRetry(String fileMd5, String requesterId) {
        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
            .orElseThrow(() -> new RuntimeException("文件不存在"));

        markVectorizationProcessing(fileUpload, true);

        FileProcessingTask task = new FileProcessingTask(fileUpload.getFileMd5(), null, fileUpload.getFileName(),
            fileUpload.getUserId(), fileUpload.getOrgTag(), fileUpload.isPublic(),
            FileProcessingTaskEnum.TASK_TYPE_REINDEX.getTaskType(), requesterId);

        kafkaTemplate.executeInTransaction(kt -> {
            kt.send(kafkaConfig.getFileProcessingTopic(), task);
            return true;
        });

        return fileUpload;
    }

    /**
     * 查询用户可访问的文件列表：先回填历史数据的向量化状态， 再按"本人上传 / 公开 / 同组织标签（含层级展开）"的并集查询，最后按 MD5+用户去重
     *
     * @param userId
     *            用户 ID（数字主键或用户名）
     * @param orgTags
     *            组织标签字符串（当前实现以用户实际有效标签为准查询）
     * @return 去重后的可访问文件列表
     */
    public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
        try {
            backfillLegacyVectorizationStatuses();

            User user = resolveUser(userId);
            String userDbId = String.valueOf(user.getId());

            List<String> userEffectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());

            // 使用有效标签查询文件
            List<FileUpload> files;
            if (userEffectiveTags.isEmpty()) {
                // 如果用户没有任何组织标签，只返回自己的文件和公开文件
                files = fileUploadRepository.findByUserIdOrIsPublicTrue(userDbId);
            } else {
                // 查询用户可访问的所有文件（考虑层级标签）
                files = fileUploadRepository.findAccessibleFilesWithTags(userDbId, userEffectiveTags);
            }

            files = deduplicateFileUploads(files);

            return files;
        } catch (Exception e) {
            log.error("获取用户可访问文件列表失败: userId={}", userId, e);

            throw new RuntimeException("获取可访问文件列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按"fileMd5 + userId"对文件列表去重：同键多条记录时保留更优的一条， 消除分片上传等场景产生的重复记录
     *
     * @param files
     *            原始文件列表
     * @return 去重后的文件列表（保持原有相对顺序）
     */
    private List<FileUpload> deduplicateFileUploads(List<FileUpload> files) {
        if (CollectionUtils.isEmpty(files) || files.size() < 2) {
            return files;
        }

        Map<String, FileUpload> deduplicated = new LinkedHashMap<>();
        int duplicateCount = 0;
        for (FileUpload file : files) {
            if (Objects.isNull(file)) {
                continue;
            }

            String key = file.getFileMd5() + ":" + file.getUserId();
            FileUpload existing = deduplicated.get(key);
            if (Objects.isNull(existing)) {
                deduplicated.put(key, file);
                continue;
            }

            duplicateCount++;
            deduplicated.put(key, choosePreferredFileUpload(existing, file));
        }

        if (duplicateCount > 0) {
            log.warn("检测到重复文件记录，列表返回前已合并: duplicateCount={}, uniqueCount={}", duplicateCount, deduplicated.size());
        }

        return new ArrayList<>(deduplicated.values());
    }

    /**
     * 从两条同键重复记录中选择保留的一条，优先级依次为： 已完成状态 > 合并时间新 > 创建时间新 > 主键大
     *
     * @param current
     *            当前保留的记录
     * @param candidate
     *            竞争的候选记录
     * @return 更优的一条记录
     */
    private FileUpload choosePreferredFileUpload(FileUpload current, FileUpload candidate) {
        if (Objects.isNull(current)) {
            return candidate;
        }

        if (Objects.isNull(candidate)) {
            return current;
        }

        boolean currentCompleted = current.getStatus() == FileUploadStatusEnum.STATUS_COMPLETED.getValue();
        boolean candidateCompleted = candidate.getStatus() == FileUploadStatusEnum.STATUS_COMPLETED.getValue();
        if (currentCompleted != candidateCompleted) {
            return candidateCompleted ? candidate : current;
        }

        int mergedAtCompare = compareNullableDateTime(candidate.getMergedAt(), current.getMergedAt());
        if (mergedAtCompare > 0) {
            return candidate;
        }

        int createdAtCompare = compareNullableDateTime(candidate.getCreatedAt(), current.getCreatedAt());
        if (createdAtCompare > 0) {
            return candidate;
        }

        if (Objects.nonNull(candidate.getId()) && Objects.nonNull(current.getId())
            && candidate.getId() > current.getId()) {
            return candidate;
        }

        return current;
    }

    /**
     * 比较两个可空时间：null 视为最小值，双方都为 null 时相等
     *
     * @param left
     *            左侧时间
     * @param right
     *            右侧时间
     * @return 负数表示 left 早于 right，0 表示相等，正数表示 left 晚于 right
     */
    private int compareNullableDateTime(LocalDateTime left, LocalDateTime right) {
        if (Objects.isNull(left) && Objects.isNull(right)) {
            return 0;
        }

        if (Objects.isNull(left)) {
            return -1;
        }

        if (Objects.isNull(right)) {
            return 1;
        }

        return left.compareTo(right);
    }

    /**
     * 解析用户：userId 兼容数字主键与用户名两种形式，均无法命中时抛出异常
     *
     * @param userId
     *            数字主键或用户名
     * @return 用户实体
     */
    private User resolveUser(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);

            return userRepository.findById(userIdLong).orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
        } catch (NumberFormatException ignored) {
            return userRepository.findByUsername(userId).orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
        }
    }

    /**
     * 回填历史数据的向量化状态：查询所有状态为空的记录并推断补齐
     */
    private void backfillLegacyVectorizationStatuses() {
        backfillLegacyVectorizationStatuses(fileUploadRepository.findAllByVectorizationStatusIsNull());
    }

    /**
     * 为向量化状态为空的历史记录推断并回填状态： 有实际用量 -> 已完成；有向量记录 -> 已完成（附历史数据提示）； 有预估值但无向量记录 -> 失败（附历史数据提示）
     *
     * @param files
     *            待回填的上传记录（只会处理状态为空的记录）
     */
    private void backfillLegacyVectorizationStatuses(List<FileUpload> files) {
        if (CollectionUtils.isEmpty(files)) {
            return;
        }

        Map<String, Long> vectorCountCache = new HashMap<>();
        for (FileUpload file : files) {
            if (Objects.isNull(file) || StringUtils.isNotBlank(file.getVectorizationStatus())) {
                continue;
            }

            boolean changed = false;
            if (file.getStatus() != FileUploadStatusEnum.STATUS_COMPLETED.getValue()) {
                continue;
            }

            if (Objects.nonNull(file.getActualEmbeddingTokens()) || Objects.nonNull(file.getActualChunkCount())) {
                file.setVectorizationStatus(
                    FileUploadVectorizationStatusEnum.VECTORIZATION_STATUS_COMPLETED.getVectorizationStatus());
                file.setVectorizationErrorMessage(null);
                changed = true;
            } else {
                long vectorCount =
                    vectorCountCache.computeIfAbsent(file.getFileMd5(), documentVectorRepository::countByFileMd5);
                if (vectorCount > 0) {
                    file.setVectorizationStatus(
                        FileUploadVectorizationStatusEnum.VECTORIZATION_STATUS_COMPLETED.getVectorizationStatus());
                    file.setVectorizationErrorMessage(LEGACY_COMPLETED_WITHOUT_USAGE_MESSAGE);
                    changed = true;
                } else if (Objects.nonNull(file.getEstimatedEmbeddingTokens())
                    || Objects.nonNull(file.getEstimatedChunkCount())) {
                    file.setVectorizationStatus(
                        FileUploadVectorizationStatusEnum.VECTORIZATION_STATUS_FAILED.getVectorizationStatus());
                    file.setVectorizationErrorMessage(LEGACY_FAILED_MESSAGE);
                    changed = true;
                }
            }

            if (changed) {
                fileUploadRepository.save(file);
            }
        }
    }

    /**
     * 查询用户上传的文件列表（含回填历史向量化状态与去重）
     *
     * @param userId
     *            用户 ID
     * @return 去重后的上传文件列表
     */
    public List<FileUpload> getUserUploadedFiles(String userId) {
        try {
            backfillLegacyVectorizationStatuses();
            List<FileUpload> files = fileUploadRepository.findByUserId(userId);
            files = deduplicateFileUploads(files);

            return files;
        } catch (Exception e) {
            log.warn("获取用户上传的文件列表失败: userId={}", userId, e);

            throw new RuntimeException("获取用户上传的文件列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成文件下载链接（1 小时有效的预签名 URL，并转换为公网域名）； 优先使用 MD5 对象路径，失败时降级使用旧文件名路径（兼容历史数据），生成失败返回 null
     *
     * @param fileMd5
     *            文件 MD5
     * @return 预签名下载 URL，失败时为 null
     */
    public String generateDownloadUrl(String fileMd5) {
        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            // 优先使用新的MD5路径
            String objectName = "merged/" + fileMd5;

            try {
                // 尝试使用新路径（MD5）
                String presignedUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket("uploads").object(objectName).expiry(3600).build());

                // 使用 publicUrl 公开域名来替换原始域名
                presignedUrl = uploadService.transToPublicUrl(presignedUrl);

                return presignedUrl;
            } catch (Exception e) {
                log.warn("使用新路径生成下载链接失败，尝试使用旧路径（文件名）: fileMd5={}", fileMd5);

                // 降级：尝试使用旧的文件名路径（兼容旧数据）
                String oldObjectName = "merged/" + fileUpload.getFileName();
                String presignedUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket("uploads").object(oldObjectName).expiry(3600).build());

                presignedUrl = uploadService.transToPublicUrl(presignedUrl);

                return presignedUrl;
            }
        } catch (Exception e) {
            log.warn("生成文件下载链接失败: fileMd5={}", fileMd5, e);

            return null;
        }
    }

    /**
     * 获取文本类文件的预览内容：读取前 10KB 文本（超出部分截断提示）， 非文本文件返回基础信息说明，读取失败时返回带失败原因的提示
     *
     * @param fileMd5
     *            文件 MD5
     * @param fileName
     *            文件名（用于判定类型）
     * @return 预览文本
     */
    public String getFilePreviewContent(String fileMd5, String fileName) {
        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            // 判断文件类型
            String fileExtension = getFileExtension(fileName).toLowerCase();
            boolean isTextFile = isTextFile(fileExtension);

            if (isTextFile) {
                // 对于文本文件，读取前10KB内容
                try (InputStream inputStream = openFileStream(fileUpload);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    int bytesRead = 0;
                    int maxBytes = 10240; // 10KB

                    while (StringUtils.isNotBlank(line = reader.readLine()) && bytesRead < maxBytes) {
                        content.append(line).append("\n");
                        bytesRead += line.getBytes("UTF-8").length + 1;
                    }

                    String result = content.toString();
                    if (bytesRead >= maxBytes) {
                        result += "\n... (内容已截断，仅显示前10KB)";
                    }

                    return result;
                }
            } else {
                // 对于非文本文件，返回文件信息
                String fileInfo = String.format(
                    "文件名: %s\n" + "文件大小: %s\n" + "文件类型: %s\n" + "上传时间: %s\n\n" + "此文件类型不支持预览，请下载后查看。", fileName,
                    formatFileSize(fileUpload.getTotalSize()), fileExtension.toUpperCase(), fileUpload.getCreatedAt());

                return fileInfo;
            }

        } catch (Exception e) {
            log.warn("获取文件预览内容失败: fileMd5={}, fileName={}", fileMd5, fileName, e);

            return "预览失败: " + e.getMessage();
        }
    }

    /**
     * 将字节数格式化为人类可读的文件大小（B/KB/MB/GB）
     *
     * @param size
     *            字节数，可为 null
     * @return 格式化后的大小字符串，null 时返回"未知"
     */
    private String formatFileSize(Long size) {
        if (Objects.isNull(size)) {
            return "未知";
        }

        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * 打开合并后的文件流：优先使用 MD5 对象路径，失败时降级使用旧文件名路径（兼容历史数据）
     *
     * @param fileUpload
     *            上传记录
     * @return MinIO 对象输入流
     * @throws Exception
     *             新旧路径都获取失败时抛出
     */
    private InputStream openFileStream(FileUpload fileUpload) throws Exception {
        String objectName = "merged/" + fileUpload.getFileMd5();

        try {
            InputStream inputStream =
                minioClient.getObject(GetObjectArgs.builder().bucket("uploads").object(objectName).build());

            return inputStream;
        } catch (Exception e) {
            log.warn("使用新路径获取文件失败，尝试使用旧路径（文件名）: fileMd5={}, error={}", fileUpload.getFileMd5(), e.getMessage());

            String oldObjectName = "merged/" + fileUpload.getFileName();
            InputStream inputStream =
                minioClient.getObject(GetObjectArgs.builder().bucket("uploads").object(oldObjectName).build());

            return inputStream;
        }
    }

    /**
     * 判断扩展名是否属于可直接以文本预览的类型（忽略大小写）
     *
     * @param extension
     *            文件扩展名
     * @return true 表示支持文本预览
     */
    private boolean isTextFile(String extension) {
        String[] textExtensions = {"txt", "md", "html", "htm", "xml", "json", "csv", "log", "java", "js", "ts", "py",
            "cpp", "c", "h", "css", "scss", "less", "sql", "yml", "yaml", "properties", "conf", "config"};

        return Arrays.stream(textExtensions).anyMatch(ext -> ext.equalsIgnoreCase(extension));
    }

    /**
     * 提取文件扩展名（不含点），无扩展名时返回空字符串
     *
     * @param fileName
     *            文件名
     * @return 扩展名（原始大小写）
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }

        return fileName.substring(lastDotIndex + 1);
    }

    /**
     * 获取 PDF 单页预览：按"本地内存 -> Redis -> 实时生成"的两级缓存顺序获取， 实时生成时用 PDFBox 抽取指定页导出为独立单页 PDF，并写回两级缓存
     *
     * @param fileMd5
     *            文件 MD5
     * @param pageNumber
     *            页码（从 1 开始，超出范围抛出异常）
     * @return 单页预览结果（内容 + 缓存命中标记）
     */
    public PdfSinglePagePreview getPdfSinglePagePreview(String fileMd5, int pageNumber) {
        try {
            String cacheKey = buildPdfSinglePageCacheKey(fileMd5, pageNumber);

            // 一级缓存：本地内存
            byte[] localPreview = getLocalPdfSinglePagePreview(cacheKey);
            if (ArrayUtils.isEmpty(localPreview)) {

                return new PdfSinglePagePreview(localPreview, true);
            }

            // 二级缓存：Redis
            byte[] cachedPreview = getCachedPdfSinglePagePreview(cacheKey);
            if (!ArrayUtils.isEmpty(cachedPreview)) {
                cacheLocalPdfSinglePagePreview(cacheKey, cachedPreview);

                return new PdfSinglePagePreview(cachedPreview, true);
            }

            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            // 缓存未命中：从 MinIO 拉取原文件，抽取指定页导出为独立单页 PDF
            try (InputStream inputStream = openFileStream(fileUpload);
                PDDocument sourceDocument = PDDocument.load(inputStream);
                PDDocument singlePageDocument = new PDDocument();
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

                int totalPages = sourceDocument.getNumberOfPages();
                if (pageNumber < 1 || pageNumber > totalPages) {
                    throw new IllegalArgumentException("页码超出范围: " + pageNumber + "/" + totalPages);
                }

                singlePageDocument.importPage(sourceDocument.getPage(pageNumber - 1));
                singlePageDocument.save(outputStream);

                byte[] previewBytes = outputStream.toByteArray();
                cacheLocalPdfSinglePagePreview(cacheKey, previewBytes);
                cachePdfSinglePagePreview(cacheKey, previewBytes);

                return new PdfSinglePagePreview(previewBytes, false);
            }
        } catch (Exception e) {
            log.error("生成 PDF 单页预览失败: fileMd5={}, pageNumber={}", fileMd5, pageNumber, e);

            throw new RuntimeException("生成 PDF 单页预览失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将单页预览内容写入 Redis 二级缓存（Base64 编码，带 TTL），写入失败仅记录日志
     *
     * @param cacheKey
     *            缓存 key
     * @param previewBytes
     *            预览内容字节流
     */
    private void cachePdfSinglePagePreview(String cacheKey, byte[] previewBytes) {
        try {
            String encodedPreview = Base64.getEncoder().encodeToString(previewBytes);
            stringRedisTemplate.opsForValue().set(cacheKey, encodedPreview, PDF_SINGLE_PAGE_CACHE_TTL_MINUTES,
                TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("写入 PDF 单页预览缓存失败: cacheKey={}, error={}", cacheKey, e.getMessage());
        }
    }

    /**
     * 将单页预览内容写入本地一级内存缓存（带过期时间戳）
     *
     * @param cacheKey
     *            缓存 key
     * @param previewBytes
     *            预览内容字节流
     */
    private void cacheLocalPdfSinglePagePreview(String cacheKey, byte[] previewBytes) {
        PDF_SINGLE_PAGE_LOCAL_CACHE.put(cacheKey,
            new InMemoryPdfPreviewCache(previewBytes, System.currentTimeMillis() + PDF_SINGLE_PAGE_CACHE_TTL_MILLIS));
    }

    /**
     * 从 Redis 读取单页预览缓存并解码为字节流； 值外层可能带有 JSON 序列化产生的双引号，读取时先剥离再解码，读取失败返回 null
     *
     * @param cacheKey
     *            缓存 key
     * @return 预览内容字节流，未命中或解码失败时为 null
     */
    private byte[] getCachedPdfSinglePagePreview(String cacheKey) {
        try {
            String normalizedValue = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StringUtils.isNotBlank(normalizedValue)) {
                normalizedValue = normalizedValue.trim();
                if (normalizedValue.startsWith("\"") && normalizedValue.endsWith("\"")
                    && normalizedValue.length() >= 2) {
                    normalizedValue = normalizedValue.substring(1, normalizedValue.length() - 1);
                }

                return Base64.getDecoder().decode(normalizedValue);
            }
        } catch (Exception e) {
            log.warn("读取 PDF 单页预览缓存失败: cacheKey={}, error={}", cacheKey, e.getMessage());
        }

        return null;
    }

    /**
     * 从本地一级缓存读取单页预览内容，过期条目顺手清除并返回 null
     *
     * @param cacheKey
     *            缓存 key
     * @return 预览内容字节流，未命中或已过期时为 null
     */
    private byte[] getLocalPdfSinglePagePreview(String cacheKey) {
        InMemoryPdfPreviewCache cached = PDF_SINGLE_PAGE_LOCAL_CACHE.get(cacheKey);
        if (Objects.isNull(cached)) {
            return null;
        }

        if (cached.getExpiresAtMillis() <= System.currentTimeMillis()) {
            PDF_SINGLE_PAGE_LOCAL_CACHE.remove(cacheKey);

            return null;
        }

        return cached.getContent();
    }

    /**
     * 构造单页预览缓存 key：前缀 + fileMd5 + ":" + pageNumber
     *
     * @param fileMd5
     *            文件 MD5
     * @param pageNumber
     *            页码
     * @return 缓存 key
     */
    private String buildPdfSinglePageCacheKey(String fileMd5, int pageNumber) {
        return PDF_SINGLE_PAGE_CACHE_PREFIX + fileMd5 + ":" + pageNumber;
    }
}
