package com.jingluo.paismart.controller;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.jingluo.paismart.config.KafkaConfig;
import com.jingluo.paismart.domain.request.MergeRequest;
import com.jingluo.paismart.domain.response.EmbeddingEstimate;
import com.jingluo.paismart.domain.response.FileTypeValidationResult;
import com.jingluo.paismart.domain.response.ResponseResult;
import com.jingluo.paismart.enums.FileProcessingTaskEnum;
import com.jingluo.paismart.enums.FileUploadStatusEnum;
import com.jingluo.paismart.enums.FileUploadVectorizationStatusEnum;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.FileProcessingTask;
import com.jingluo.paismart.model.FileUpload;
import com.jingluo.paismart.model.OrganizationTag;
import com.jingluo.paismart.repository.FileUploadRepository;
import com.jingluo.paismart.service.FileTypeValidationService;
import com.jingluo.paismart.service.ParseService;
import com.jingluo.paismart.service.UploadService;
import com.jingluo.paismart.service.UserService;

import io.minio.GetObjectResponse;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 13:50
 * @Desc: 文件上传控制器：提供分片上传、上传进度查询、分片合并触发向量化及支持类型查询接口
 */
@RestController
@RequestMapping("/api/v1/upload")
public class UploadController {

    @Autowired
    private FileTypeValidationService fileTypeValidationService;

    @Autowired
    private UserService userService;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private ParseService parseService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private KafkaConfig kafkaConfig;

    /**
     * 默认分片大小：5MB，用于估算已上传字节数以校验组织上传限额
     */
    private static final long DEFAULT_CHUNK_SIZE_BYTES = 5L * 1024 * 1024L;

    /**
     * 分片上传接口：第一个分片时校验文件类型，非管理员校验组织上传限额，保存分片并返回上传进度
     *
     * @param fileMd5
     *            文件整体 MD5，作为分片与合并的唯一标识
     * @param chunkIndex
     *            当前分片序号（从 0 开始）
     * @param totalSize
     *            文件总大小（字节）
     * @param fileName
     *            文件名
     * @param totalChunks
     *            分片总数（可选）
     * @param orgTag
     *            组织标签，为空时取用户主组织
     * @param isPublic
     *            是否公开文件
     * @param file
     *            分片文件内容
     * @param userId
     *            当前登录用户 ID
     * @return 已上传分片列表与上传进度
     */
    @PostMapping("/chunk")
    public ResponseResult uploadChunk(@RequestParam("fileMd5") String fileMd5,
        @RequestParam("chunkIndex") int chunkIndex, @RequestParam("totalSize") long totalSize,
        @RequestParam("fileName") String fileName,
        @RequestParam(value = "totalChunks", required = false) Integer totalChunks,
        @RequestParam(value = "orgTag", required = false) String orgTag,
        @RequestParam(value = "isPublic", required = false, defaultValue = "false") boolean isPublic,
        @RequestParam("file") MultipartFile file, @RequestAttribute("userId") String userId) throws IOException {
        // 文件类型验证（仅在第一个分片时进行验证）
        if (chunkIndex == 0) {
            FileTypeValidationResult validationResult = fileTypeValidationService.validateFileType(fileName);
            if (!validationResult.isValid()) {
                Map<String, Object> map = Map.of("fileType", validationResult.getFileType(), "supportedTypes",
                    fileTypeValidationService.getSupportedFileTypes());

                return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), validationResult.getMessage(), map);
            }
        }

        // 如果未指定组织标签，则获取用户的主组织标签
        if (StringUtils.isBlank(orgTag)) {
            try {
                String primaryOrg = userService.getUserPrimaryOrg(userId);
                orgTag = primaryOrg;
            } catch (Exception e) {
                return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "获取用户主组织标签失败: " + e.getMessage());
            }
        }

        if (!userService.isAdminUser(userId)) {
            OrganizationTag uploadOrg = userService.getOrganizationTag(orgTag);

            Long uploadMaxSizeBytes = uploadOrg.getUploadMaxSizeBytes();

            long estimatedUploadedBytes = (long)chunkIndex * DEFAULT_CHUNK_SIZE_BYTES + file.getSize();

            boolean exceedsLimit = uploadMaxSizeBytes != null && uploadMaxSizeBytes > 0
                && (totalSize > uploadMaxSizeBytes || estimatedUploadedBytes > uploadMaxSizeBytes);

            if (exceedsLimit) {
                Map<String, ? extends Serializable> map =
                    Map.of("limitBytes", uploadMaxSizeBytes, "fileSizeBytes", totalSize, "orgTag", orgTag);

                return ResponseResult.fail(HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    "当前组织限制非管理员上传文件不超过 " + formatSize(uploadMaxSizeBytes) + "，当前文件大小为 " + formatSize(totalSize), map);
            }
        }

        uploadService.uploadChunk(fileMd5, chunkIndex, totalSize, fileName, file, orgTag, isPublic, userId);

        List<Integer> uploadedChunks = uploadService.getUploadedChunks(fileMd5, userId);

        int actualTotalChunks = uploadService.getTotalChunks(fileMd5, userId);

        double progress = calculateProgress(uploadedChunks, actualTotalChunks);

        // 构建数据对象
        Map<String, Object> map = Map.of("uploaded", uploadedChunks, "progress", progress);

        return ResponseResult.success(map);
    }

    /**
     * 计算上传进度百分比，分片总数为 0 时返回 0
     */
    private double calculateProgress(List<Integer> uploadedChunks, int totalChunks) {
        if (totalChunks == 0) {
            return 0.0;
        }

        return (double)uploadedChunks.size() / totalChunks * 100;
    }

    /**
     * 将字节数格式化为 KB/MB/GB 的可读字符串，用于限额提示
     */
    private String formatSize(long sizeInBytes) {
        double sizeInMb = sizeInBytes / (1024d * 1024d);

        if (sizeInMb >= 1024d) {
            return String.format("%.2f GB", sizeInMb / 1024d);
        }

        if (sizeInMb >= 1d) {
            return String.format("%.2f MB", sizeInMb);
        }

        return String.format("%.2f KB", sizeInBytes / 1024d);
    }

    /**
     * 查询分片上传状态：返回已上传分片序号、进度及文件名、文件类型等信息
     *
     * @param fileMd5
     *            文件 MD5
     * @param userId
     *            当前登录用户 ID
     * @return 上传状态信息
     */
    @GetMapping("/status")
    public ResponseResult getUploadStatus(@RequestParam("file_md5") String fileMd5,
        @RequestAttribute("userId") String userId) {
        // 获取文件信息
        String fileName = "unknown";
        String fileType = "unknown";
        try {
            Optional<FileUpload> fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
            if (fileUpload.isPresent()) {
                fileName = fileUpload.get().getFileName();
                fileType = getFileType(fileName);
            }
        } catch (Exception e) {
            // 获取文件信息失败不影响状态查询，继续处理
        }

        List<Integer> uploadedChunks = uploadService.getUploadedChunks(fileMd5, userId);
        int totalChunks = uploadService.getTotalChunks(fileMd5, userId);
        double progress = calculateProgress(uploadedChunks, totalChunks);

        // 构建数据对象
        Map<String, Object> data = new HashMap<>();
        data.put("uploaded", uploadedChunks);
        data.put("progress", progress);
        data.put("fileName", fileName);
        data.put("fileType", fileType);

        return ResponseResult.success(data);
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
     * 分片合并接口：校验权限与分片完整性，通过条件状态更新防止并发重复合并， 合并后估算 Embedding 用量并向 Kafka 派发文件处理（向量化）任务
     *
     * @param request
     *            合并请求（文件 MD5 与文件名）
     * @param userId
     *            当前登录用户 ID
     * @return 合并后的文件预签名访问地址与预估 Embedding 用量
     */
    @PostMapping("/merge")
    public ResponseResult mergeFile(@RequestBody MergeRequest request, @RequestAttribute("userId") String userId) {
        try {
            // 检查文件完整性和权限
            FileUpload fileUpload =
                fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(request.getFileMd5(), userId)
                    .orElseThrow(() -> new RuntimeException("文件记录不存在"));

            // 确保用户有权限操作该文件
            if (!fileUpload.getUserId().equals(userId)) {

                return ResponseResult.fail(HttpStatus.FORBIDDEN.value(), "没有权限操作此文件");
            }

            if (fileUpload.getStatus() == FileUploadStatusEnum.STATUS_COMPLETED.getValue()) {
                return buildAlreadyMergedResponse(request.getFileMd5());
            }

            if (fileUpload.getStatus() == FileUploadStatusEnum.STATUS_MERGING.getValue()) {
                throw new CustomException("文件正在合并中，请稍后重试", HttpStatus.CONFLICT);
            }

            // 检查分片是否全部上传完成
            List<Integer> uploadedChunks = uploadService.getUploadedChunks(request.getFileMd5(), userId);
            int totalChunks = uploadService.getTotalChunks(request.getFileMd5(), userId);

            if (uploadedChunks.size() < totalChunks) {
                return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), "文件分片未全部上传，无法合并");
            }

            int updatedRows = fileUploadRepository.updateStatusIfCurrent(fileUpload.getId(),
                FileUploadStatusEnum.STATUS_UPLOADING.getValue(), FileUploadStatusEnum.STATUS_MERGING.getValue());

            if (updatedRows == 0) {
                FileUpload latestFileUpload =
                    fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(request.getFileMd5(), userId)
                        .orElseThrow(() -> new RuntimeException("文件记录不存在"));

                if (latestFileUpload.getStatus() == FileUploadStatusEnum.STATUS_COMPLETED.getValue()) {
                    return buildAlreadyMergedResponse(request.getFileMd5());
                }

                if (latestFileUpload.getStatus() == FileUploadStatusEnum.STATUS_MERGING.getValue()) {
                    throw new CustomException("文件正在合并中，请稍后重试", HttpStatus.CONFLICT);
                }

                throw new CustomException("文件状态已变化，请刷新后重试", HttpStatus.CONFLICT);
            }

            fileUpload =
                fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(request.getFileMd5(), userId)
                    .orElseThrow(() -> new RuntimeException("文件记录不存在"));

            // 合并文件
            String objectUrl;
            try {
                objectUrl = uploadService.mergeChunks(request.getFileMd5(), request.getFileName(), userId);
            } catch (Exception mergeException) {
                fileUploadRepository.updateStatusIfCurrent(fileUpload.getId(),
                    FileUploadStatusEnum.STATUS_MERGING.getValue(), FileUploadStatusEnum.STATUS_UPLOADING.getValue());

                throw mergeException;
            }

            fileUpload =
                fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(request.getFileMd5(), userId)
                    .orElseThrow(() -> new RuntimeException("文件记录不存在"));

            EmbeddingEstimate embeddingEstimate = null;
            try (GetObjectResponse mergedFileStream = uploadService.getMergedFileStream(request.getFileMd5())) {
                embeddingEstimate = parseService.estimateEmbeddingUsage(mergedFileStream);
                fileUpload.setEstimatedEmbeddingTokens(embeddingEstimate.getEstimatedTokens());
                fileUpload.setEstimatedChunkCount(embeddingEstimate.getEstimatedChunkCount());

                fileUploadRepository.save(fileUpload);
            } catch (Exception estimateException) {
                // Embedding 用量估算失败不影响合并结果与向量化主流程，忽略该异常
            }

            // 发送任务到 Kafka，包含完整的权限信息
            FileProcessingTask task = new FileProcessingTask(request.getFileMd5(), objectUrl, request.getFileName(),
                fileUpload.getUserId(), fileUpload.getOrgTag(), fileUpload.isPublic(),
                FileProcessingTaskEnum.TASK_TYPE_UPLOAD_PROCESS.getTaskType(), userId);

            fileUpload.setVectorizationStatus(
                FileUploadVectorizationStatusEnum.VECTORIZATION_STATUS_PROCESSING.getVectorizationStatus());
            fileUpload.setVectorizationErrorMessage(null);
            fileUpload.setActualEmbeddingTokens(null);
            fileUpload.setActualChunkCount(null);

            fileUploadRepository.save(fileUpload);

            kafkaTemplate.executeInTransaction(kt -> {
                kt.send(kafkaConfig.getFileProcessingTopic(), task);
                return true;
            });

            // 构建数据对象
            Map<String, Object> data = new HashMap<>();
            data.put("object_url", objectUrl);
            if (Objects.nonNull(embeddingEstimate)) {
                data.put("estimatedEmbeddingTokens", embeddingEstimate.getEstimatedTokens());
                data.put("estimatedChunkCount", embeddingEstimate.getEstimatedChunkCount());
            }

            return ResponseResult.success(data);
        } catch (Exception e) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "合并文件时出错");
        }
    }

    /**
     * 构建文件已合并场景的响应：直接返回已合并文件的预签名访问地址，保证接口幂等
     */
    private ResponseResult buildAlreadyMergedResponse(String fileMd5) throws Exception {
        return ResponseResult.success(uploadService.generateMergedObjectUrl(fileMd5));
    }

    /**
     * 查询系统支持的文件类型列表及扩展名集合，供前端上传组件做类型限制提示
     *
     * @return 支持的文件类型描述、扩展名与说明
     */
    @GetMapping("/supported-types")
    public ResponseResult getSupportedFileTypes() {
        Set<String> supportedTypes = fileTypeValidationService.getSupportedFileTypes();

        Set<String> supportedExtensions = fileTypeValidationService.getSupportedExtensions();

        // 构建数据对象
        Map<String, Object> data = new HashMap<>();
        data.put("supportedTypes", supportedTypes);
        data.put("supportedExtensions", supportedExtensions);
        data.put("description", "系统支持的文档类型文件，这些文件可以被解析并进行向量化处理");

        return ResponseResult.success(data);
    }
}
