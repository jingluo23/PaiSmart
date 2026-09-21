package com.jingluo.paismart.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jingluo.paismart.domain.response.PdfSinglePagePreview;
import com.jingluo.paismart.domain.response.ReferenceInfo;
import com.jingluo.paismart.domain.response.RequestAuthContext;
import com.jingluo.paismart.domain.response.ResponseResult;
import com.jingluo.paismart.model.FileUpload;
import com.jingluo.paismart.model.OrganizationTag;
import com.jingluo.paismart.repository.FileUploadRepository;
import com.jingluo.paismart.repository.OrganizationTagRepository;
import com.jingluo.paismart.service.ChatHandler;
import com.jingluo.paismart.service.DocumentService;
import com.jingluo.paismart.utils.JwtUtils;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 8:51
 * @Desc: 文档管理控制器，提供文档删除、索引重建、向量化重试、 可访问文件列表查询、文件下载/预览及 AI 回答引用详情等接口
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ChatHandler chatHandler;

    /**
     * 删除文档：按 MD5 定位当前用户最新上传的文件，仅文件所有者或管理员有权删除
     *
     * @param fileMd5
     *            文件 MD5
     * @param userId
     *            当前用户 ID
     * @param role
     *            当前用户角色
     * @return 删除结果
     */
    @DeleteMapping("/{fileMd5}")
    public ResponseResult<?> deleteDocument(@PathVariable String fileMd5, @RequestAttribute("userId") String userId,
        @RequestAttribute("role") String role) {
        // 获取文件信息
        Optional<FileUpload> fileOpt =
            fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId);
        if (fileOpt.isEmpty()) {
            return ResponseResult.fail(HttpStatus.NOT_FOUND.value(), "文档不存在");
        }

        FileUpload file = fileOpt.get();

        // 权限检查：只有文件所有者或管理员可以删除
        if (!file.getUserId().equals(userId) && !"ADMIN".equals(role)) {
            return ResponseResult.fail(HttpStatus.FORBIDDEN.value(), "没有权限删除此文档");
        }

        // 执行删除操作
        documentService.deleteDocument(fileMd5, userId);

        return ResponseResult.success("文档删除成功");
    }

    /**
     * 重建文档索引：重新解析文件并同步执行向量化，返回实际用量
     *
     * @param fileMd5
     *            文件 MD5
     * @param userId
     *            当前用户 ID
     * @param role
     *            当前用户角色
     * @return 重建结果（文件信息 + 实际 Tokens / 分块数 / 模型版本）
     */
    @PostMapping("/{fileMd5}/reindex")
    public ResponseResult<?> reindexDocument(@PathVariable String fileMd5, @RequestAttribute("userId") String userId,
        @RequestAttribute("role") String role) {
        Optional<FileUpload> fileOpt = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
        if (fileOpt.isEmpty()) {
            return ResponseResult.fail(HttpStatus.NOT_FOUND.value(), "文档不存在");
        }

        FileUpload file = fileOpt.get();
        if (!file.getUserId().equals(userId) && !"ADMIN".equals(role)) {
            return ResponseResult.fail(HttpStatus.FORBIDDEN.value(), "没有权限重建此文档索引");
        }

        var result = documentService.reindexDocument(fileMd5, userId);

        Map<String, Object> data = new HashMap<>();
        data.put("fileMd5", fileMd5);
        data.put("fileName", file.getFileName());
        data.put("actualEmbeddingTokens", result.getActualEmbeddingTokens());
        data.put("actualChunkCount", result.getActualChunkCount());
        data.put("modelVersion", result.getModelVersion());

        return ResponseResult.success(data);
    }

    /**
     * 异步重试向量化：投递重建任务到 Kafka，由消费端异步执行，立即返回受理状态
     *
     * @param fileMd5
     *            文件 MD5
     * @param userId
     *            当前用户 ID
     * @param role
     *            当前用户角色
     * @return 受理结果（文件信息 + 当前向量化状态）
     */
    @PostMapping("/{fileMd5}/vectorization/retry")
    public ResponseResult<?> retryVectorizationAsync(@PathVariable String fileMd5,
        @RequestAttribute("userId") String userId, @RequestAttribute("role") String role) {
        Optional<FileUpload> fileOpt = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
        if (fileOpt.isEmpty()) {
            return ResponseResult.fail(HttpStatus.NOT_FOUND.value(), "文档不存在");
        }

        FileUpload file = fileOpt.get();
        if (!file.getUserId().equals(userId) && !"ADMIN".equals(role)) {
            return ResponseResult.fail(HttpStatus.FORBIDDEN.value(), "没有权限重试此文档向量化");
        }

        FileUpload queuedFile = documentService.enqueueAsyncVectorizationRetry(fileMd5, userId);

        Map<String, Object> data = new HashMap<>();
        data.put("fileMd5", queuedFile.getFileMd5());
        data.put("fileName", queuedFile.getFileName());
        data.put("vectorizationStatus", queuedFile.getVectorizationStatus());

        return ResponseResult.success(data);
    }

    /**
     * 查询当前用户可访问的文件列表（本人上传、公开文件、同组织标签文件）， 支持可选的服务端分页
     *
     * @param userId
     *            当前用户 ID
     * @param orgTags
     *            当前用户组织标签（逗号分隔）
     * @param page
     *            页码（可选，从 1 开始）
     * @param size
     *            每页条数（可选）
     * @return 文件列表（分页参数缺省时返回全量列表）
     */
    @GetMapping("/accessible")
    public ResponseResult<?> getAccessibleFiles(@RequestAttribute("userId") String userId,
        @RequestAttribute("orgTags") String orgTags, @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size) {
        List<FileUpload> files = documentService.getAccessibleFiles(userId, orgTags);

        List<Map<String, Object>> fileData = convertFilesToResponse(files);

        Object data = (Objects.nonNull(page) || Objects.nonNull(size)) ? paginateList(fileData, page, size) : fileData;

        return ResponseResult.success(data);
    }

    /**
     * 对内存中的列表执行分页裁剪，并组装兼容 Spring Data 分页结构的返回体
     *
     * @param records
     *            全量记录列表
     * @param page
     *            页码（从 1 开始，非法值按 1 处理）
     * @param size
     *            每页条数（非法值按 10 处理）
     * @return 分页结果（data/content 双字段兼容不同前端取值方式）
     */
    private Map<String, Object> paginateList(List<Map<String, Object>> records, Integer page, Integer size) {
        int pageNumber = Objects.isNull(page) || page < 1 ? 1 : page;
        int pageSize = Objects.isNull(size) || size < 1 ? 10 : size;
        int total = records.size();
        int fromIndex = Math.min((pageNumber - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<Map<String, Object>> pageData = records.subList(fromIndex, toIndex);

        Map<String, Object> result = new HashMap<>();
        result.put("data", pageData);
        result.put("content", pageData);
        result.put("number", pageNumber);
        result.put("size", pageSize);
        result.put("totalElements", total);

        return result;
    }

    /**
     * 将文件实体列表转换为前端响应结构，附带组织标签名称
     *
     * @param files
     *            文件实体列表
     * @return 文件信息列表（含向量化状态、预估/实际用量等字段）
     */
    private List<Map<String, Object>> convertFilesToResponse(List<FileUpload> files) {
        return files.stream().map(file -> {
            Map<String, Object> dto = new HashMap<>();
            dto.put("id", file.getId());
            dto.put("fileMd5", file.getFileMd5());
            dto.put("fileName", file.getFileName());
            dto.put("totalSize", file.getTotalSize());
            dto.put("status", file.getStatus());
            dto.put("userId", file.getUserId());
            dto.put("orgTag", file.getOrgTag());
            dto.put("public", file.isPublic());
            dto.put("isPublic", file.isPublic());
            dto.put("createdAt", file.getCreatedAt());
            dto.put("mergedAt", file.getMergedAt());
            dto.put("estimatedEmbeddingTokens", file.getEstimatedEmbeddingTokens());
            dto.put("estimatedChunkCount", file.getEstimatedChunkCount());
            dto.put("actualEmbeddingTokens", file.getActualEmbeddingTokens());
            dto.put("actualChunkCount", file.getActualChunkCount());
            dto.put("vectorizationStatus", file.getVectorizationStatus());
            dto.put("vectorizationErrorMessage", file.getVectorizationErrorMessage());
            dto.put("orgTagName", getOrgTagName(file.getOrgTag()));
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 查询组织标签的展示名称，查询失败或不存在时回退返回原 tagId
     *
     * @param tagId
     *            组织标签 ID
     * @return 标签名称或原 tagId
     */
    private String getOrgTagName(String tagId) {
        if (StringUtils.isBlank(tagId)) {
            return null;
        }

        try {
            Optional<OrganizationTag> tagOpt = organizationTagRepository.findByTagId(tagId);
            if (tagOpt.isPresent()) {
                return tagOpt.get().getName();
            } else {
                // 如果找不到标签名称，返回原tagId
                return tagId;
            }
        } catch (Exception e) {
            // 发生错误时返回原tagId
            return tagId;
        }
    }

    /**
     * 查询当前用户上传的文件列表
     *
     * @param userId
     *            当前用户 ID
     * @return 用户上传的文件信息列表
     */
    @GetMapping("/uploads")
    public ResponseResult<?> getUserUploadedFiles(@RequestAttribute("userId") String userId) {
        List<FileUpload> files = documentService.getUserUploadedFiles(userId);

        List<Map<String, Object>> fileData = convertFilesToResponse(files);

        return ResponseResult.success(fileData);
    }

    /**
     * 按文件名生成下载链接：匿名请求仅允许下载公开文件，登录用户可下载其可访问的文件
     *
     * @param fileName
     *            文件名
     * @param authorization
     *            Authorization 请求头（Bearer token，可选）
     * @param token
     *            URL 参数形式的 token（可选，用于无法携带请求头的场景）
     * @return 文件名、下载链接与文件大小
     */
    @GetMapping("/download")
    public ResponseResult<?> downloadFileByName(@RequestParam String fileName,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(required = false) String token) {
        // 验证token并获取用户信息
        RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
        String userId = authContext.getUserId();
        String orgTags = authContext.getOrgTags();

        // 如果没有提供token或token无效，只允许下载公开文件
        if (StringUtils.isBlank(userId)) {
            // 查找公开文件
            Optional<FileUpload> publicFile =
                fileUploadRepository.findFirstByFileNameAndIsPublicTrueOrderByCreatedAtDesc(fileName);
            if (publicFile.isEmpty()) {
                return ResponseResult.fail(HttpStatus.NOT_FOUND.value(), "文件不存在或需要登录访问");
            }

            FileUpload file = publicFile.get();
            String downloadUrl = documentService.generateDownloadUrl(file.getFileMd5());

            if (StringUtils.isBlank(downloadUrl)) {
                return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "无法生成下载链接");
            }

            Map<String, Object> map =
                Map.of("fileName", file.getFileName(), "downloadUrl", downloadUrl, "fileSize", file.getTotalSize());

            return ResponseResult.success(map);
        }

        // 有token的情况，查找用户可访问的文件
        List<FileUpload> accessibleFiles = documentService.getAccessibleFiles(userId, orgTags);

        // 根据文件名查找匹配的文件
        Optional<FileUpload> targetFile =
            accessibleFiles.stream().filter(file -> file.getFileName().equals(fileName)).findFirst();

        if (targetFile.isEmpty()) {
            return ResponseResult.fail(HttpStatus.NOT_FOUND.value(), "文件不存在或无权限访问");
        }

        FileUpload file = targetFile.get();

        // 生成下载链接或返回预签名URL
        String downloadUrl = documentService.generateDownloadUrl(file.getFileMd5());

        if (StringUtils.isBlank(downloadUrl)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "无法生成下载链接");
        }

        Map<String, Object> response =
            Map.of("fileName", file.getFileName(), "downloadUrl", downloadUrl, "fileSize", file.getTotalSize());

        return ResponseResult.success(response);
    }

    /**
     * 从 Authorization 头或 URL 参数中解析 JWT，提取用户 ID 与组织标签； 两种来源都未携带有效 token 时返回匿名上下文（字段为 null）
     *
     * @param authorization
     *            Authorization 请求头（Bearer token）
     * @param fallbackToken
     *            备用的 URL 参数 token
     * @return 鉴权上下文
     */
    private RequestAuthContext resolveRequestAuthContext(String authorization, String fallbackToken) {
        String jwtToken = jwtUtils.extractBearerToken(authorization);
        if (StringUtils.isBlank(jwtToken) && StringUtils.isNotBlank(fallbackToken)) {
            jwtToken = fallbackToken.trim();
        }

        if (StringUtils.isBlank(jwtToken)) {
            return new RequestAuthContext(null, null);
        }

        return new RequestAuthContext(jwtUtils.extractUserIdFromToken(jwtToken),
            jwtUtils.extractOrgTagsFromToken(jwtToken));
    }

    /**
     * 预览文件内容
     *
     * @param fileName
     *            文件名
     * @param fileMd5
     *            文件MD5（可选，用于精确定位同名文件）
     * @param token
     *            JWT token (URL参数，用于向后兼容)
     * @return 文件预览内容或错误响应
     */
    @GetMapping("/preview")
    public ResponseResult<?> previewFileByName(@RequestParam String fileName,
        @RequestParam(required = false) String fileMd5, @RequestParam(required = false) Integer pageNumber,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(required = false) String token) {
        // 验证token并获取用户信息
        RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
        String userId = authContext.getUserId();
        String orgTags = authContext.getOrgTags();

        FileUpload file = null;

        // 如果没有提供token或token无效，只允许预览公开文件
        if (StringUtils.isBlank(userId)) {
            // 优先使用MD5查找（如果提供）
            if (StringUtils.isNotBlank(fileMd5) && !fileMd5.trim().isEmpty()) {
                Optional<FileUpload> fileByMd5 =
                    fileUploadRepository.findFirstByFileMd5AndIsPublicTrueOrderByCreatedAtDesc(fileMd5);
                if (fileByMd5.isPresent()) {
                    file = fileByMd5.get();
                }
            }

            // 如果MD5未找到或未提供，降级到文件名查找
            if (Objects.isNull(file)) {
                Optional<FileUpload> publicFile =
                    fileUploadRepository.findFirstByFileNameAndIsPublicTrueOrderByCreatedAtDesc(fileName);
                if (publicFile.isPresent()) {
                    file = publicFile.get();
                }
            }

            if (Objects.isNull(file)) {
                return ResponseResult.fail(HttpStatus.NOT_FOUND.value(), "文件不存在或需要登录访问");
            }

            Map<String, Object> previewData = buildPreviewResponse(file, pageNumber, false);
            if (CollectionUtils.isEmpty(previewData)) {
                return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "无法获取文件预览内容");
            }

            return ResponseResult.success(previewData);
        }

        // 有token的情况，查找用户可访问的文件
        List<FileUpload> accessibleFiles = documentService.getAccessibleFiles(userId, orgTags);

        // 优先使用MD5查找（如果提供）
        Optional<FileUpload> targetFile = Optional.empty();
        if (StringUtils.isNotBlank(fileMd5) && !fileMd5.trim().isEmpty()) {
            final String md5 = fileMd5;
            targetFile = accessibleFiles.stream().filter(f -> f.getFileMd5().equals(md5)).findFirst();
        }

        // 如果MD5未找到或未提供，降级到文件名查找
        if (targetFile.isEmpty()) {
            targetFile = accessibleFiles.stream().filter(f -> f.getFileName().equals(fileName)).findFirst();
        }

        if (targetFile.isEmpty()) {
            return ResponseResult.fail(HttpStatus.NOT_FOUND.value(), "文件不存在或无权限访问");
        }

        file = targetFile.get();

        // 获取文件预览内容
        Map<String, Object> previewData = buildPreviewResponse(file, pageNumber, true);
        if (CollectionUtils.isEmpty(previewData)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "无法获取文件预览内容");
        }

        return ResponseResult.success(previewData);
    }

    /**
     * 组装文件预览响应：按扩展名判定预览类型——文本文件直接返回内容， 其余类型返回预览链接；登录用户预览 PDF 指定页码时切换为单页预览链接
     *
     * @param file
     *            目标文件
     * @param pageNumber
     *            请求的 PDF 页码（可选）
     * @param preferSinglePagePreview
     *            是否优先使用 PDF 单页预览（仅登录态生效）
     * @return 预览数据；无法生成预览时返回 null
     */
    private Map<String, Object> buildPreviewResponse(FileUpload file, Integer pageNumber,
        boolean preferSinglePagePreview) {
        String fileName = file.getFileName();

        String extension = getFileExtension(fileName);

        String previewType = getPreviewType(extension);

        Map<String, Object> payload = new HashMap<>();
        payload.put("fileName", fileName);
        payload.put("fileMd5", file.getFileMd5());
        payload.put("fileSize", file.getTotalSize());
        payload.put("previewType", previewType);

        if ("text".equals(previewType)) {
            String previewContent = documentService.getFilePreviewContent(file.getFileMd5(), fileName);
            if (previewContent == null) {
                return null;
            }
            payload.put("content", previewContent);
            return payload;
        }

        String previewUrl = documentService.generateDownloadUrl(file.getFileMd5());
        if (previewUrl == null) {
            return null;
        }

        if (preferSinglePagePreview && "pdf".equals(previewType) && pageNumber != null && pageNumber > 0) {
            payload.put("previewUrl", buildSinglePagePreviewUrl(file.getFileMd5(), pageNumber));
            payload.put("sourceUrl", previewUrl);
            payload.put("singlePageMode", true);
            payload.put("sourcePageNumber", pageNumber);

            return payload;
        }

        payload.put("previewUrl", previewUrl);

        return payload;
    }

    /**
     * 构造 PDF 单页预览接口的相对 URL（fileMd5 需 URL 编码）
     *
     * @param fileMd5
     *            文件 MD5
     * @param pageNumber
     *            页码
     * @return 单页预览 URL
     */
    private String buildSinglePagePreviewUrl(String fileMd5, Integer pageNumber) {
        return "/api/v1/documents/page-preview?fileMd5=" + URLEncoder.encode(fileMd5, StandardCharsets.UTF_8)
            + "&pageNumber=" + pageNumber;
    }

    /**
     * 根据文件扩展名判定预览类型：pdf / image / text / download
     *
     * @param extension
     *            文件扩展名（不含点）
     * @return 预览类型，无法预览的类型返回 download
     */
    private String getPreviewType(String extension) {
        if (StringUtils.isBlank(extension)) {
            return "download";
        }

        String lowerCaseExtension = extension.toLowerCase();
        if ("pdf".equals(lowerCaseExtension)) {
            return "pdf";
        }

        if (List.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg").contains(lowerCaseExtension)) {
            return "image";
        }

        if (List.of("txt", "md", "json", "xml", "csv", "html", "htm", "css", "js", "java", "py", "sql", "yaml", "yml")
            .contains(lowerCaseExtension)) {
            return "text";
        }

        return "download";
    }

    /**
     * 提取文件扩展名（不含点），无扩展名或以点结尾时返回空字符串
     *
     * @param fileName
     *            文件名
     * @return 扩展名（原始大小写）
     */
    private String getFileExtension(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }

        return fileName.substring(dotIndex + 1);
    }

    /**
     * PDF 单页预览：提取指定页生成独立单页 PDF，内容以 Base64 返回， 并携带缓存命中状态（HIT/MISS）
     *
     * @param fileMd5
     *            文件 MD5
     * @param pageNumber
     *            页码（从 1 开始）
     * @param userId
     *            当前用户 ID
     * @param orgTags
     *            当前用户组织标签
     * @return 单页 PDF 内容（Base64）与缓存状态
     */
    @GetMapping("/page-preview")
    public ResponseResult<?> previewPdfPage(@RequestParam String fileMd5, @RequestParam Integer pageNumber,
        @RequestAttribute("userId") String userId, @RequestAttribute("orgTags") String orgTags) {
        FileUpload file = documentService.getAccessibleFiles(userId, orgTags).stream()
            .filter(item -> item.getFileMd5().equals(fileMd5)).findFirst().orElse(null);

        if (Objects.isNull(file)) {
            return ResponseResult.fail(HttpStatus.NOT_FOUND.value(), "文件不存在或无权限访问");
        }

        if (!"pdf".equalsIgnoreCase(getFileExtension(file.getFileName()))) {
            return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), "仅支持 PDF 单页预览");
        }

        PdfSinglePagePreview preview = documentService.getPdfSinglePagePreview(fileMd5, pageNumber);
        byte[] pdfBytes = preview.getContent();

        Map<String, Object> data = new HashMap<>();
        data.put("fileMd5", fileMd5);
        data.put("fileName", file.getFileName());
        data.put("pageNumber", pageNumber);
        data.put("contentType", MediaType.APPLICATION_PDF_VALUE);
        data.put("contentBase64", Base64.getEncoder().encodeToString(pdfBytes));
        data.put("cacheStatus", preview.isCacheHit() ? "HIT" : "MISS");

        return ResponseResult.success(data);
    }

    /**
     * 按文件 MD5 生成下载链接：匿名请求仅允许下载公开文件，登录用户可下载其可访问的文件
     *
     * @param fileMd5
     *            文件 MD5
     * @param authorization
     *            Authorization 请求头（Bearer token，可选）
     * @param token
     *            URL 参数形式的 token（可选）
     * @return 文件名、下载链接、文件大小与文件 MD5
     */
    @GetMapping("/download-by-md5")
    public ResponseResult<?> downloadFileByMd5(@RequestParam String fileMd5,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestParam(required = false) String token) {
        RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
        String userId = authContext.getUserId();
        String orgTags = authContext.getOrgTags();

        FileUpload file;
        if (StringUtils.isBlank(userId)) {
            file = fileUploadRepository.findFirstByFileMd5AndIsPublicTrueOrderByCreatedAtDesc(fileMd5).orElse(null);
        } else {
            file = documentService.getAccessibleFiles(userId, orgTags).stream()
                .filter(item -> item.getFileMd5().equals(fileMd5)).findFirst().orElse(null);
        }

        if (Objects.isNull(file)) {
            return ResponseResult.fail(HttpStatus.NOT_FOUND.value(), "文件不存在或无权限访问");
        }

        String downloadUrl = documentService.generateDownloadUrl(file.getFileMd5());
        if (StringUtils.isBlank(downloadUrl)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "无法生成下载链接");
        }

        Map<String, Object> response = Map.of("fileName", file.getFileName(), "downloadUrl", downloadUrl, "fileSize",
            file.getTotalSize(), "fileMd5", file.getFileMd5());

        return ResponseResult.success(response);
    }

    /**
     * 查询 AI 回答中指定引用的详情；携带有效 token 时会校验用户对引用文件的可访问性
     *
     * @param sessionId
     *            会话/生成任务 ID
     * @param referenceNumber
     *            引用编号（回答文本中的上标序号）
     * @param authorization
     *            Authorization 请求头（Bearer token，可选）
     * @return 引用详情（文件、页码、锚点、命中分块原文等）
     */
    @GetMapping("/reference-detail")
    public ResponseResult<?> getReferenceDetail(@RequestParam String sessionId, @RequestParam Integer referenceNumber,
        @RequestHeader(value = "Authorization", required = false) String authorization) {
        ReferenceInfo detail = chatHandler.getReferenceDetail(sessionId, referenceNumber);
        if (Objects.isNull(detail)) {
            return ResponseResult.fail(HttpStatus.NOT_FOUND.value(), "未找到对应的文件引用");
        }

        RequestAuthContext authContext = resolveRequestAuthContext(authorization, null);
        if (StringUtils.isNotBlank(authContext.getUserId())) {
            boolean hasAccess = documentService.getAccessibleFiles(authContext.getUserId(), authContext.getOrgTags())
                .stream().anyMatch(file -> file.getFileMd5().equals(detail.getFileMd5()));
            if (!hasAccess) {
                return ResponseResult.fail(HttpStatus.FORBIDDEN.value(), "无权限访问该引用文件");
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("fileMd5", detail.getFileMd5());
        data.put("fileName", detail.getFileName());
        data.put("referenceNumber", referenceNumber);
        data.put("pageNumber", detail.getPageNumber());
        data.put("anchorText", detail.getAnchorText());
        data.put("retrievalMode", detail.getRetrievalMode());
        data.put("retrievalLabel", detail.getRetrievalLabel());
        data.put("retrievalQuery", detail.getRetrievalQuery());
        data.put("matchedChunkText", detail.getMatchedChunkText());
        data.put("evidenceSnippet", detail.getEvidenceSnippet());
        data.put("score", detail.getScore());
        data.put("chunkId", detail.getChunkId());

        return ResponseResult.success(data);
    }
}
