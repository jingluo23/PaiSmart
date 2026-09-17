package com.jingluo.paismart.service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.jingluo.paismart.enums.FileUploadStatusEnum;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.ChunkInfo;
import com.jingluo.paismart.model.FileUpload;
import com.jingluo.paismart.repository.ChunkInfoRepository;
import com.jingluo.paismart.repository.FileUploadRepository;

import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 14:19
 * @Desc: 文件分片上传服务：负责分片写入 MinIO、分片状态管理（Redis Bitmap + 数据库）、服务端分片合并及预签名 URL 生成
 */
@Slf4j
@Service
public class UploadService {

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ChunkInfoRepository chunkInfoRepository;

    @Autowired
    private MinioClient minioClient;

    /**
     * 文件记录创建锁：按 userId+fileMd5 粒度防止并发上传首个分片时重复创建文件记录
     */
    private static final ConcurrentHashMap<String, Object> FILE_UPLOAD_CREATE_LOCKS = new ConcurrentHashMap<>();

    /**
     * 上传单个分片：创建或获取文件记录，校验状态后写入 MinIO 与分片信息表； 三处状态（Redis 标记、分片记录、MinIO 对象）一致的已传分片按幂等成功处理， 状态不一致时先清理过期状态再重新上传
     *
     * @param fileMd5
     *            文件整体 MD5
     * @param chunkIndex
     *            分片序号（从 0 开始）
     * @param totalSize
     *            文件总大小（字节）
     * @param fileName
     *            文件名
     * @param file
     *            分片内容
     * @param orgTag
     *            组织标签
     * @param isPublic
     *            是否公开文件
     * @param userId
     *            上传用户 ID
     * @throws IOException
     *             读取分片内容失败
     */
    public void uploadChunk(String fileMd5, int chunkIndex, long totalSize, String fileName, MultipartFile file,
        String orgTag, boolean isPublic, String userId) throws IOException {
        // 获取文件类型信息
        String fileType = getFileType(fileName);

        try {
            FileUpload fileUpload =
                getOrCreateFileUpload(fileMd5, totalSize, fileName, orgTag, isPublic, userId, fileType);

            if (fileUpload.getStatus() == FileUploadStatusEnum.STATUS_MERGING.getValue()) {
                throw new CustomException("文件正在合并中，请稍后重试", HttpStatus.CONFLICT);
            }
            if (fileUpload.getStatus() == FileUploadStatusEnum.STATUS_COMPLETED.getValue()) {
                throw new CustomException("文件已完成合并，不允许继续上传分片", HttpStatus.CONFLICT);
            }

            String storagePath = buildChunkStoragePath(fileMd5, chunkIndex);

            // Redis Bitmap 是上传进度快路径；数据库 + MinIO 对象共同决定分片是否可用于合并。
            boolean chunkUploaded = isChunkUploaded(fileMd5, chunkIndex, userId);
            boolean chunkInfoExists = chunkInfoRepository.existsByFileMd5AndChunkIndex(fileMd5, chunkIndex);

            if (chunkUploaded && chunkInfoExists && chunkObjectExists(storagePath, fileMd5, fileName, chunkIndex)) {
                return;
            }

            if (chunkUploaded || chunkInfoExists) {
                clearStaleChunkState(fileMd5, chunkIndex, userId, fileName, storagePath);
            }

            if (chunkInfoRepository.existsByFileMd5AndChunkIndex(fileMd5, chunkIndex)
                && chunkObjectExists(storagePath, fileMd5, fileName, chunkIndex)) {
                markChunkUploadedQuietly(fileMd5, chunkIndex, userId, fileName);

                return;
            }

            byte[] fileBytes = file.getBytes();
            String chunkMd5 = DigestUtils.md5Hex(fileBytes);

            try {
                PutObjectArgs putObjectArgs = PutObjectArgs.builder().bucket("uploads").object(storagePath)
                    .stream(file.getInputStream(), file.getSize(), -1).contentType(file.getContentType()).build();

                minioClient.putObject(putObjectArgs);
            } catch (Exception e) {
                if (e instanceof io.minio.errors.ErrorResponseException) {
                    ErrorResponseException ere = (ErrorResponseException)e;
                }

                throw new RuntimeException("上传分片到MinIO失败: " + e.getMessage(), e);
            }

            saveChunkInfo(fileMd5, chunkIndex, chunkMd5, storagePath);

            markChunkUploadedQuietly(fileMd5, chunkIndex, userId, fileName);
        } catch (Exception e) {
            // 重新抛出异常供上层处理
            throw e;
        }
    }

    /**
     * 保存分片信息记录，唯一约束冲突（并发重复写入）时按幂等成功处理
     */
    private void saveChunkInfo(String fileMd5, int chunkIndex, String chunkMd5, String storagePath) {
        try {
            ChunkInfo chunkInfo = new ChunkInfo();
            chunkInfo.setFileMd5(fileMd5);
            chunkInfo.setChunkIndex(chunkIndex);
            chunkInfo.setChunkMd5(chunkMd5);
            chunkInfo.setStoragePath(storagePath);

            chunkInfoRepository.save(chunkInfo);
        } catch (DataIntegrityViolationException e) {
            log.warn("分片信息已存在，按幂等成功处理 => fileMd5: {}, chunkIndex: {}", fileMd5, chunkIndex);
        } catch (Exception e) {
            throw new RuntimeException("保存分片信息失败", e);
        }
    }

    /**
     * 标记分片已上传，失败仅记录告警：数据库分片记录仍作为合并的事实来源
     */
    private void markChunkUploadedQuietly(String fileMd5, int chunkIndex, String userId, String fileName) {
        try {
            markChunkUploaded(fileMd5, chunkIndex, userId);
        } catch (Exception e) {
            log.warn("标记分片已上传失败，数据库仍作为事实来源 => fileMd5: {}, fileName: {}, chunkIndex: {}, 错误: {}", fileMd5, fileName,
                chunkIndex, e.getMessage(), e);
        }
    }

    /**
     * 在 Redis Bitmap 中标记分片为已上传（上传进度查询的快速路径）
     *
     * @param fileMd5
     *            文件 MD5
     * @param chunkIndex
     *            分片序号
     * @param userId
     *            上传用户 ID
     */
    public void markChunkUploaded(String fileMd5, int chunkIndex, String userId) {
        try {
            if (chunkIndex < 0) {
                throw new IllegalArgumentException("无效的分片索引");
            }

            String redisKey = "upload:" + userId + ":" + fileMd5;
            redisTemplate.opsForValue().setBit(redisKey, chunkIndex, true);
        } catch (Exception e) {
            throw new RuntimeException("标记分片为已上传失败", e);
        }
    }

    /**
     * 清理不一致的分片状态：Redis 标记存在但 MinIO 对象或分片记录缺失时回退标记， 让该分片走重新上传流程
     */
    private void clearStaleChunkState(String fileMd5, int chunkIndex, String userId, String fileName,
        String storagePath) {
        if (!chunkObjectExists(storagePath, fileMd5, fileName, chunkIndex)) {
            clearChunkUploadedQuietly(fileMd5, chunkIndex, userId, fileName);

            return;
        }

        if (!chunkInfoRepository.existsByFileMd5AndChunkIndex(fileMd5, chunkIndex)) {
            clearChunkUploadedQuietly(fileMd5, chunkIndex, userId, fileName);
        }
    }

    /**
     * 清除 Redis Bitmap 中的分片上传标记，失败仅记录告警
     */
    private void clearChunkUploadedQuietly(String fileMd5, int chunkIndex, String userId, String fileName) {
        try {
            String redisKey = "upload:" + userId + ":" + fileMd5;
            redisTemplate.opsForValue().setBit(redisKey, chunkIndex, false);
        } catch (Exception e) {
            log.warn("清理 Redis 分片标记失败，将继续重新上传分片 => fileMd5: {}, fileName: {}, chunkIndex: {}, error: {}", fileMd5,
                fileName, chunkIndex, e.getMessage());
        }
    }

    /**
     * 检查分片对象在 MinIO 中是否真实存在
     */
    private boolean chunkObjectExists(String storagePath, String fileMd5, String fileName, int chunkIndex) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket("uploads").object(storagePath).build());

            return Boolean.TRUE;
        } catch (Exception e) {
            return Boolean.FALSE;
        }
    }

    /**
     * 查询 Redis Bitmap 判断分片是否已标记上传，查询异常时返回 false 以触发安全重传
     *
     * @param fileMd5
     *            文件 MD5
     * @param chunkIndex
     *            分片序号
     * @param userId
     *            上传用户 ID
     * @return 分片是否已标记上传
     */
    public boolean isChunkUploaded(String fileMd5, int chunkIndex, String userId) {
        try {
            if (chunkIndex < 0) {
                throw new IllegalArgumentException("无效的分片索引");
            }

            String redisKey = "upload:" + userId + ":" + fileMd5;

            return Boolean.TRUE.equals(redisTemplate.opsForValue().getBit(redisKey, chunkIndex));
        } catch (Exception e) {
            // 或者根据业务需求返回其他值
            return Boolean.FALSE;
        }
    }

    /**
     * 生成分片在 MinIO 中的存储路径：chunks/{fileMd5}/{chunkIndex}
     */
    private String buildChunkStoragePath(String fileMd5, int chunkIndex) {
        return "chunks/" + fileMd5 + "/" + chunkIndex;
    }

    /**
     * 获取或创建文件上传记录：先无锁查询，未命中时按 userId+fileMd5 粒度加进程内锁， 双重检查后创建，唯一约束冲突时回查并发创建的结果
     */
    private FileUpload getOrCreateFileUpload(String fileMd5, long totalSize, String fileName, String orgTag,
        boolean isPublic, String userId, String fileType) {
        Optional<FileUpload> existingFileUpload =
            fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId);
        if (existingFileUpload.isPresent()) {
            return existingFileUpload.get();
        }

        String lockKey = userId + ":" + fileMd5;
        Object createLock = FILE_UPLOAD_CREATE_LOCKS.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (createLock) {
            try {
                existingFileUpload =
                    fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId);
                if (existingFileUpload.isPresent()) {
                    return existingFileUpload.get();
                }

                FileUpload fileUpload = new FileUpload();
                fileUpload.setFileMd5(fileMd5);
                fileUpload.setFileName(fileName);
                fileUpload.setTotalSize(totalSize);
                fileUpload.setStatus(FileUploadStatusEnum.STATUS_UPLOADING.getValue());
                fileUpload.setUserId(userId);
                fileUpload.setOrgTag(orgTag);
                fileUpload.setPublic(isPublic);
                fileUpload.setVectorizationStatus(null);
                fileUpload.setVectorizationErrorMessage(null);

                try {
                    return fileUploadRepository.save(fileUpload);
                } catch (DataIntegrityViolationException e) {
                    return fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                        .orElseThrow(() -> new RuntimeException("文件记录并发创建后查询失败", e));
                } catch (Exception e) {
                    throw new RuntimeException("创建文件记录失败: " + e.getMessage(), e);
                }
            } finally {
                FILE_UPLOAD_CREATE_LOCKS.remove(lockKey, createLock);
            }
        }
    }

    /**
     * 根据文件扩展名返回对应的文件类型中文描述
     */
    private String getFileType(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return "unknown";
        }

        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "unknown";
        }

        String extension = fileName.substring(lastDotIndex + 1).toLowerCase();

        // 根据文件扩展名返回文件类型
        switch (extension) {
            case "pdf":
                return "PDF文档";
            case "doc":
            case "docx":
                return "Word文档";
            case "xls":
            case "xlsx":
                return "Excel表格";
            case "ppt":
            case "pptx":
                return "PowerPoint演示文稿";
            case "txt":
                return "文本文件";
            case "md":
                return "Markdown文档";
            case "jpg":
            case "jpeg":
                return "JPEG图片";
            case "png":
                return "PNG图片";
            case "gif":
                return "GIF图片";
            case "bmp":
                return "BMP图片";
            case "svg":
                return "SVG图片";
            case "mp4":
                return "MP4视频";
            case "avi":
                return "AVI视频";
            case "mov":
                return "MOV视频";
            case "wmv":
                return "WMV视频";
            case "mp3":
                return "MP3音频";
            case "wav":
                return "WAV音频";
            case "flac":
                return "FLAC音频";
            case "zip":
                return "ZIP压缩包";
            case "rar":
                return "RAR压缩包";
            case "7z":
                return "7Z压缩包";
            case "tar":
                return "TAR压缩包";
            case "gz":
                return "GZ压缩包";
            case "json":
                return "JSON文件";
            case "xml":
                return "XML文件";
            case "csv":
                return "CSV文件";
            case "html":
            case "htm":
                return "HTML文件";
            case "css":
                return "CSS文件";
            case "js":
                return "JavaScript文件";
            case "java":
                return "Java源码";
            case "py":
                return "Python源码";
            case "cpp":
            case "c":
                return "C/C++源码";
            case "sql":
                return "SQL文件";
            default:
                return extension.toUpperCase() + "文件";
        }
    }

    /**
     * 获取已上传分片列表：优先一次 IO 读取 Redis Bitmap 全量解析， Bitmap 缺失或为空时回查数据库分片记录并回填 Bitmap
     *
     * @param fileMd5
     *            文件 MD5
     * @param userId
     *            上传用户 ID
     * @return 已上传分片序号列表
     */
    public List<Integer> getUploadedChunks(String fileMd5, String userId) {
        List<Integer> uploadedChunks = new ArrayList<>();
        try {
            int totalChunks = getTotalChunks(fileMd5, userId);

            if (Objects.isNull(totalChunks)) {
                return uploadedChunks;
            }

            // 优化：一次性获取所有分片状态
            String redisKey = "upload:" + userId + ":" + fileMd5;
            byte[] bitmapData =
                redisTemplate.execute((RedisCallback<byte[]>)connection -> connection.get(redisKey.getBytes()));

            if (ArrayUtils.isEmpty(bitmapData)) {
                List<Integer> dbUploadedChunks = getUploadedChunksFromDatabase(fileMd5);
                if (!dbUploadedChunks.isEmpty()) {
                    backfillUploadedChunks(fileMd5, dbUploadedChunks, userId);
                }

                return dbUploadedChunks;
            }

            if (bitmapData.length == 0) {
                List<Integer> dbUploadedChunks = getUploadedChunksFromDatabase(fileMd5);
                if (!dbUploadedChunks.isEmpty()) {
                    backfillUploadedChunks(fileMd5, dbUploadedChunks, userId);
                }

                return dbUploadedChunks;
            }

            // 解析bitmap，找出已上传的分片
            for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                if (isBitSet(bitmapData, chunkIndex)) {
                    uploadedChunks.add(chunkIndex);
                }
            }

            if (uploadedChunks.isEmpty()) {
                List<Integer> dbUploadedChunks = getUploadedChunksFromDatabase(fileMd5);
                if (!dbUploadedChunks.isEmpty()) {
                    backfillUploadedChunks(fileMd5, dbUploadedChunks, userId);

                    return dbUploadedChunks;
                }

                return uploadedChunks;
            }

            return uploadedChunks;
        } catch (Exception e) {
            throw new RuntimeException("获取已上传分片列表失败", e);
        }
    }

    /**
     * 判断 Bitmap 字节数组中指定位是否为 1（Redis 位序为高位在前）
     */
    private boolean isBitSet(byte[] bitmapData, int bitIndex) {
        try {
            int byteIndex = bitIndex / 8;
            // Redis bitmap的位顺序是从高位到低位
            int bitPosition = 7 - (bitIndex % 8);

            if (byteIndex >= bitmapData.length) {
                // 超出范围的位默认为0
                return Boolean.FALSE;
            }

            return (bitmapData[byteIndex] & (1 << bitPosition)) != 0;
        } catch (Exception e) {
            return Boolean.FALSE;
        }
    }

    /**
     * 将数据库中已存在的分片序号回填到 Redis Bitmap，修复标记丢失
     */
    private void backfillUploadedChunks(String fileMd5, List<Integer> uploadedChunks, String userId) {
        for (Integer chunkIndex : uploadedChunks) {
            if (chunkIndex != null && chunkIndex >= 0) {
                markChunkUploadedQuietly(fileMd5, chunkIndex, userId, "unknown");
            }
        }
    }

    /**
     * 从分片信息表查询已上传分片序号（数据库作为最终事实来源）
     */
    private List<Integer> getUploadedChunksFromDatabase(String fileMd5) {
        return chunkInfoRepository.findChunkIndexesByFileMd5(fileMd5);
    }

    /**
     * 根据文件总大小计算总分片数（每片 5MB 向上取整）
     *
     * @param fileMd5
     *            文件 MD5
     * @param userId
     *            上传用户 ID
     * @return 总分片数，文件记录不存在时返回 0
     */
    public int getTotalChunks(String fileMd5, String userId) {
        try {
            Optional<FileUpload> fileUpload =
                fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId);

            if (fileUpload.isEmpty()) {
                return 0;
            }

            long totalSize = fileUpload.get().getTotalSize();
            // 默认每个分片5MB
            int chunkSize = 5 * 1024 * 1024;
            int totalChunks = (int)Math.ceil((double)totalSize / chunkSize);

            return totalChunks;
        } catch (Exception e) {
            throw new RuntimeException("计算文件总分片数失败", e);
        }
    }

    /**
     * 生成已合并文件的预签名访问 URL（有效期 1 小时）
     *
     * @param fileMd5
     *            文件 MD5
     * @return 预签名访问地址
     */
    public String generateMergedObjectUrl(String fileMd5) throws Exception {
        return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
            .bucket("uploads").object("merged/" + fileMd5).expiry(1, TimeUnit.HOURS).build());
    }

    /**
     * 合并分片：校验分片数量与各分片对象存在性后调用 MinIO 服务端合并（compose）， 成功后清理分片对象与 Redis 标记，并将文件状态更新为已完成
     *
     * @param fileMd5
     *            文件 MD5
     * @param fileName
     *            文件名
     * @param userId
     *            上传用户 ID
     * @return 合并后文件的预签名访问 URL
     */
    public String mergeChunks(String fileMd5, String fileName, String userId) {
        try {
            // 查询所有分片信息
            List<ChunkInfo> chunks = chunkInfoRepository.findByFileMd5OrderByChunkIndexAsc(fileMd5);

            // 检查分片数量是否与预期一致
            int expectedChunks = getTotalChunks(fileMd5, userId);
            if (chunks.size() != expectedChunks) {
                throw new RuntimeException(String.format("分片数量不匹配，期望: %d, 实际: %d", expectedChunks, chunks.size()));
            }

            List<String> partPaths = chunks.stream().map(ChunkInfo::getStoragePath).collect(Collectors.toList());

            // 检查每个分片是否存在
            for (int i = 0; i < partPaths.size(); i++) {
                String path = partPaths.get(i);
                try {
                    StatObjectResponse stat =
                        minioClient.statObject(StatObjectArgs.builder().bucket("uploads").object(path).build());
                } catch (Exception e) {
                    throw new RuntimeException("分片 " + i + " 不存在或无法访问: " + e.getMessage(), e);
                }
            }

            // 使用 MD5 作为 MinIO 对象路径，确保同名不同内容的文件不会互相覆盖
            String mergedPath = "merged/" + fileMd5;

            try {
                // 合并分片
                List<ComposeSource> sources =
                    partPaths.stream().map(path -> ComposeSource.builder().bucket("uploads").object(path).build())
                        .collect(Collectors.toList());

                minioClient.composeObject(
                    ComposeObjectArgs.builder().bucket("uploads").object(mergedPath).sources(sources).build());

                // 检查合并后的文件
                StatObjectResponse stat =
                    minioClient.statObject(StatObjectArgs.builder().bucket("uploads").object(mergedPath).build());

                // 清理分片文件
                for (String path : partPaths) {
                    try {
                        minioClient.removeObject(RemoveObjectArgs.builder().bucket("uploads").object(path).build());
                    } catch (Exception e) {
                        // 记录错误但不中断流程
                    }
                }

                // 删除 Redis 中的分片状态记录
                deleteFileMark(fileMd5, userId);

                // 更新文件状态
                FileUpload fileUpload =
                    fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                        .orElseThrow(() -> new RuntimeException("文件记录不存在: " + fileMd5));

                fileUpload.setStatus(FileUploadStatusEnum.STATUS_COMPLETED.getValue());
                fileUpload.setMergedAt(LocalDateTime.now());
                fileUploadRepository.save(fileUpload);

                // 生成预签名 URL（有效期为 1 小时）
                String presignedUrl = generateMergedObjectUrl(fileMd5);

                return presignedUrl;
            } catch (Exception e) {
                throw new RuntimeException("合并文件失败: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            throw new RuntimeException("文件合并失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除 Redis 中该文件的分片上传进度标记（合并成功后调用）
     *
     * @param fileMd5
     *            文件 MD5
     * @param userId
     *            上传用户 ID
     */
    public void deleteFileMark(String fileMd5, String userId) {
        try {
            String redisKey = "upload:" + userId + ":" + fileMd5;
            redisTemplate.delete(redisKey);
        } catch (Exception e) {
            throw new RuntimeException("删除文件分片上传标记失败", e);
        }
    }

    /**
     * 打开已合并文件的对象流，用于服务端读取（如 Embedding 用量估算）
     *
     * @param fileMd5
     *            文件 MD5
     * @return 合并文件的对象流，调用方负责关闭
     */
    public GetObjectResponse getMergedFileStream(String fileMd5) throws Exception {
        return minioClient.getObject(GetObjectArgs.builder().bucket("uploads").object("merged/" + fileMd5).build());
    }
}
