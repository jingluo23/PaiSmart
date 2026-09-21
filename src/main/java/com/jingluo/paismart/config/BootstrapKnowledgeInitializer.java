package com.jingluo.paismart.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.exception.TikaException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.model.FileUpload;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.DocumentVectorRepository;
import com.jingluo.paismart.repository.FileUploadRepository;
import com.jingluo.paismart.repository.UserRepository;
import com.jingluo.paismart.service.ElasticsearchService;
import com.jingluo.paismart.service.ParseService;
import com.jingluo.paismart.service.VectorizationService;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 15:51
 * @Desc: 启动知识库初始化器，应用启动时将引导文档（默认 docs/paismart.pdf）导入
 *        MinIO/ES/向量库，保证系统开箱即有可检索的基础知识；已导入且数据完整时自动跳过，
 *        文档内容变更后会清理旧数据并重新导入
 */
@Slf4j
@Component
@Order(3)
@ConditionalOnProperty(name = "knowledge.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class BootstrapKnowledgeInitializer implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private ParseService parseService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private VectorizationService vectorizationService;

    /**
     * 引导文档路径，支持绝对路径与相对于工作目录的相对路径
     */
    @Value("${knowledge.bootstrap.path:docs/paismart.pdf}")
    private String bootstrapDocumentPath;

    /**
     * 找不到管理员时的兜底归属用户ID
     */
    @Value("${knowledge.bootstrap.user-id:system-bootstrap}")
    private String bootstrapUserId;

    /**
     * 引导文档归属的组织标签
     */
    @Value("${knowledge.bootstrap.org-tag:default}")
    private String bootstrapOrgTag;

    /**
     * 引导文档是否公开（公开则所有用户可检索）
     */
    @Value("${knowledge.bootstrap.public:true}")
    private boolean bootstrapPublic;

    /**
     * 引导文档写入的 MinIO 桶名
     */
    @Value("${minio.bucketName:uploads}")
    private String minioBucketName;

    /**
     * 管理员用户名，用于优先确定引导文档的归属人
     */
    @Value("${admin.bootstrap.username:}")
    private String adminUsername;

    /**
     * 应用启动时执行导入流程：定位文档 -> 清理历史脏数据 -> 完整性检查 -> 导入
     *
     * @param args 启动参数
     * @throws Exception 文档不存在或导入过程中的 IO/解析异常
     */
    @Override
    public void run(String... args) throws Exception {
        Path documentPath = resolveDocumentPath();
        if (!Files.isRegularFile(documentPath)) {
            return;
        }

        String fileName = documentPath.getFileName().toString();

        String fileMd5 = calculateMd5(documentPath);

        long totalSize = Files.size(documentPath);

        String ownerUserId = resolveOwnerUserId();

        cleanupBootstrapHistory(fileName, fileMd5, ownerUserId);

        if (isBootstrapDocumentReady(fileMd5, fileName, ownerUserId)) {
            return;
        }

        cleanupBootstrapData(fileMd5, ownerUserId);

        importBootstrapDocument(documentPath, fileMd5, fileName, totalSize, ownerUserId);
    }

    /**
     * 导入引导文档：依次上传 MinIO -> 解析分块 -> 向量化 -> 落库文件记录，任一步骤失败均回滚已写入数据
     *
     * @param documentPath 文档路径
     * @param fileMd5      文件内容 MD5，作为唯一标识
     * @param fileName     文件名
     * @param totalSize    文件总字节数
     * @param ownerUserId  归属用户ID
     * @throws IOException  文件读取失败
     * @throws TikaException 文档解析失败
     */
    private void importBootstrapDocument(Path documentPath, String fileMd5, String fileName, long totalSize,
        String ownerUserId) throws IOException, TikaException {
        uploadToMinio(documentPath, fileMd5);

        try (InputStream inputStream = Files.newInputStream(documentPath)) {
            parseService.parseAndSave(fileMd5, inputStream, ownerUserId, bootstrapOrgTag, bootstrapPublic);
        } catch (Exception e) {
            cleanupBootstrapData(fileMd5, ownerUserId);
            throw e;
        }

        try {
            vectorizationService.vectorize(fileMd5, ownerUserId, bootstrapOrgTag, bootstrapPublic, bootstrapUserId);

            FileUpload fileUpload = fileUploadRepository
                .findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, ownerUserId).orElseGet(FileUpload::new);
            fileUpload.setFileMd5(fileMd5);
            fileUpload.setFileName(fileName);
            fileUpload.setTotalSize(totalSize);
            fileUpload.setStatus(1);// 1 表示文件已合并完成
            fileUpload.setUserId(ownerUserId);
            fileUpload.setOrgTag(bootstrapOrgTag);
            fileUpload.setPublic(bootstrapPublic);
            fileUpload.setMergedAt(LocalDateTime.now());

            fileUploadRepository.save(fileUpload);
        } catch (Exception e) {
            cleanupBootstrapData(fileMd5, ownerUserId);

            throw e;
        }
    }

    /**
     * 将引导文档以 merged/{md5} 为对象名上传到 MinIO，未识别到 Content-Type 时默认按 PDF 处理
     *
     * @param documentPath 文档路径
     * @param fileMd5      文件内容 MD5，作为对象名的一部分
     * @throws IOException 文件读取失败
     */
    private void uploadToMinio(Path documentPath, String fileMd5) throws IOException {
        String objectName = "merged/" + fileMd5;
        String contentType = Files.probeContentType(documentPath);
        if (StringUtils.isBlank(contentType)) {
            contentType = "application/pdf";
        }

        try (InputStream inputStream = Files.newInputStream(documentPath)) {
            minioClient.putObject(PutObjectArgs.builder().bucket(minioBucketName).object(objectName)
                .stream(inputStream, Files.size(documentPath), -1).contentType(contentType).build());
        } catch (Exception e) {
            throw new RuntimeException("写入 MinIO 失败", e);
        }
    }

    /**
     * 检查引导文档是否已完整导入：文件记录元数据匹配、向量分块和 ES 索引均存在、
     * 且无重复记录（PDF 额外要求存在带页码的分块）
     *
     * @param fileMd5     文件内容 MD5
     * @param fileName    文件名
     * @param ownerUserId 归属用户ID
     * @return true 表示数据完整，无需重新导入
     */
    private boolean isBootstrapDocumentReady(String fileMd5, String fileName, String ownerUserId) {
        Optional<FileUpload> existingFile =
            fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, ownerUserId);

        long fileRecordCount = fileUploadRepository.countByFileMd5AndUserId(fileMd5, ownerUserId);

        long vectorCount = documentVectorRepository.countByFileMd5(fileMd5);

        long pageAwareVectorCount = documentVectorRepository.countByFileMd5AndPageNumberIsNotNull(fileMd5);

        long esCount = elasticsearchService.countByFileMd5(fileMd5);

        if (existingFile.isEmpty()) {
            return false;
        }

        FileUpload fileUpload = existingFile.get();
        boolean metadataMatches =
            fileName.equals(fileUpload.getFileName()) && bootstrapOrgTag.equals(fileUpload.getOrgTag())
                && bootstrapPublic == fileUpload.isPublic() && fileUpload.getStatus() == 1;

        // PDF 文档要求存在带页码的分块，否则视为旧版本数据需要重新导入
        boolean pageMetadataReady = !fileName.toLowerCase().endsWith(".pdf") || pageAwareVectorCount > 0;
        if (metadataMatches && vectorCount > 0 && esCount > 0 && pageMetadataReady && fileRecordCount == 1) {
            return true;
        }

        return false;
    }

    /**
     * 清理同名文件的历史导入记录：删除内容已变更的旧 MD5 数据，并去重当前 MD5 的多余记录，
     * 避免文档更新后残留旧版本数据
     *
     * @param fileName       文件名
     * @param currentFileMd5 当前文档内容的 MD5
     * @param ownerUserId    归属用户ID
     */
    private void cleanupBootstrapHistory(String fileName, String currentFileMd5, String ownerUserId) {
        List<FileUpload> bootstrapHistory =
            fileUploadRepository.findByUserIdAndFileNameOrderByCreatedAtDesc(ownerUserId, fileName);
        if (bootstrapHistory.isEmpty()) {
            return;
        }

        boolean keptCurrentRecord = false;
        List<FileUpload> duplicateCurrentRecords = new ArrayList<>();
        Set<String> staleFileMd5s = new LinkedHashSet<>();

        for (FileUpload fileUpload : bootstrapHistory) {
            if (currentFileMd5.equals(fileUpload.getFileMd5())) {
                if (!keptCurrentRecord) {
                    keptCurrentRecord = true;
                } else {
                    duplicateCurrentRecords.add(fileUpload);
                }
            } else {
                staleFileMd5s.add(fileUpload.getFileMd5());
            }
        }

        // 清理内容已变更（旧 MD5）的全套数据
        staleFileMd5s.forEach(staleFileMd5 -> {
            cleanupBootstrapData(staleFileMd5, ownerUserId);
        });

        if (!duplicateCurrentRecords.isEmpty()) {
            fileUploadRepository.deleteAll(duplicateCurrentRecords);
        }
    }

    /**
     * 删除指定 MD5 的全套导入数据（MinIO 对象、ES 索引、向量分块、文件记录），
     * 各存储的清理互相独立，单点失败仅记录日志不中断
     *
     * @param fileMd5     文件内容 MD5
     * @param ownerUserId 归属用户ID
     */
    private void cleanupBootstrapData(String fileMd5, String ownerUserId) {
        try {
            minioClient
                .removeObject(RemoveObjectArgs.builder().bucket(minioBucketName).object("merged/" + fileMd5).build());
        } catch (Exception e) {
            log.warn("清理启动知识库 MinIO 文件失败: fileMd5={}, error={}", fileMd5, e.getMessage());
        }

        try {
            elasticsearchService.deleteByFileMd5(fileMd5);
        } catch (Exception e) {
            log.warn("清理启动知识库 ES 数据失败: fileMd5={}, error={}", fileMd5, e.getMessage());
        }

        try {
            documentVectorRepository.deleteByFileMd5(fileMd5);
        } catch (Exception e) {
            log.warn("清理启动知识库分块数据失败: fileMd5={}, error={}", fileMd5, e.getMessage());
        }

        try {
            fileUploadRepository.deleteByFileMd5AndUserId(fileMd5, ownerUserId);
        } catch (Exception e) {
            log.warn("清理启动知识库文件记录失败: fileMd5={}, error={}", fileMd5, e.getMessage());
        }
    }

    /**
     * 确定引导文档的归属用户ID：优先使用管理员账号，无管理员时退回配置的兜底用户ID
     *
     * @return 归属用户ID
     */
    private String resolveOwnerUserId() {
        Optional<User> adminUser = findAdminUser();
        if (adminUser.isPresent()) {
            String ownerUserId = String.valueOf(adminUser.get().getId());

            return ownerUserId;
        }

        return bootstrapUserId;
    }

    /**
     * 查找管理员用户：优先按配置的管理员用户名查找，未命中则取库中任意一个 ADMIN 角色用户
     *
     * @return 管理员用户，不存在时为 empty
     */
    private Optional<User> findAdminUser() {
        if (StringUtils.isNotBlank(adminUsername)) {
            Optional<User> configuredAdmin =
                userRepository.findByUsername(adminUsername).filter(user -> Role.ADMIN.equals(user.getRole()));
            if (configuredAdmin.isPresent()) {
                return configuredAdmin;
            }
        }

        return userRepository.findAll().stream().filter(user -> Role.ADMIN.equals(user.getRole())).findFirst();
    }

    /**
     * 计算文件内容的 MD5 摘要，流式读取避免大文件一次性载入内存
     *
     * @param documentPath 文档路径
     * @return 十六进制 MD5 字符串
     * @throws IOException 文件读取失败
     */
    private String calculateMd5(Path documentPath) throws IOException {
        try (InputStream inputStream = Files.newInputStream(documentPath)) {
            return DigestUtils.md5Hex(inputStream);
        }
    }

    /**
     * 解析引导文档路径：绝对路径直接使用，相对路径基于应用工作目录解析
     *
     * @return 规范化后的文档路径
     */
    private Path resolveDocumentPath() {
        Path path = Path.of(bootstrapDocumentPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }

        return Path.of(System.getProperty("user.dir")).resolve(path).normalize();
    }
}
