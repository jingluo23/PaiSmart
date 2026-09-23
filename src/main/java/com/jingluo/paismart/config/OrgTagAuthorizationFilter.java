package com.jingluo.paismart.config;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.jingluo.paismart.domain.response.ResourceInfo;
import com.jingluo.paismart.model.FileUpload;
import com.jingluo.paismart.repository.FileUploadRepository;
import com.jingluo.paismart.utils.JwtUtils;
import com.jingluo.paismart.utils.ResponseResultWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 16:42
 * @Desc: 组织标签授权过滤器，基于资源的组织标签（orgTag）进行细粒度访问控制：
 *        公开资源/默认组织直接放行；私人标签资源仅拥有者与管理员可访问；
 *        其他带组织标签的资源要求用户拥有相同标签才可访问
 */
@Slf4j
@Component
public class OrgTagAuthorizationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private ResponseResultWriter responseResultWriter;

    /**
     * 默认组织标签
     */
    private static final String DEFAULT_ORG_TAG = "DEFAULT";

    /**
     * 私人组织标签前缀
     */
    private static final String PRIVATE_TAG_PREFIX = "PRIVATE_";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        try {
            String path = request.getRequestURI();

            // 需要用户ID但不需要资源权限检查的API路径
            // 这些API只需要用户身份验证，不需要对特定资源进行权限检查
            // 控制器方法通过@RequestAttribute("userId")获取用户ID
            if (path.matches(".*/upload/chunk.*") || path.matches(".*/upload/merge.*")
                || path.matches(".*/upload/status.*") || path.matches(".*/documents/uploads.*")
                || path.matches(".*/documents/accessible.*") || path.matches(".*/documents/page-preview.*")
                || path.matches(".*/search/hybrid.*") || (path.matches(".*/documents/[a-fA-F0-9]{32}.*")
                    && ("DELETE".equals(request.getMethod()) || "POST".equals(request.getMethod())))) {

                String operation = "未知操作";
                if (path.contains("/chunk")) {
                    operation = "分片上传";
                } else if (path.contains("/merge")) {
                    operation = "合并分片";
                } else if (path.contains("/status")) {
                    operation = "获取上传状态";
                } else if (path.contains("/uploads")) {
                    operation = "获取用户文档";
                } else if (path.contains("/accessible")) {
                    operation = "获取可访问文档";
                } else if (path.contains("/page-preview")) {
                    operation = "获取 PDF 单页预览";
                } else if (path.contains("/search/hybrid")) {
                    operation = "混合检索";
                } else if ("DELETE".equals(request.getMethod()) && path.matches(".*/documents/[a-fA-F0-9]{32}.*")) {
                    operation = "删除文档";
                } else if ("POST".equals(request.getMethod())
                    && path.matches(".*/documents/[a-fA-F0-9]{32}/reindex.*")) {
                    operation = "重建文档索引";
                }

                // 将用户ID和角色设置为请求属性，供控制器方法使用
                String token = jwtUtils.extractToken(request);
                if (StringUtils.isNotBlank(token)) {
                    String userId = jwtUtils.extractUserIdFromToken(token);
                    String role = jwtUtils.extractRoleFromToken(token);
                    String orgTags = jwtUtils.extractOrgTagsFromToken(token);
                    if (StringUtils.isNotBlank(userId)) {
                        request.setAttribute("userId", userId);
                        request.setAttribute("role", role);
                        request.setAttribute("orgTags", orgTags);
                    } else {
                        log.warn("{}请求中无法从token提取userId", operation);
                    }
                } else {
                    log.warn("{}请求中未找到有效token", operation);
                }

                filterChain.doFilter(request, response);

                return;
            }

            boolean isChunkUpload = path.matches(".*/upload/chunk.*");

            // 获取路径中的资源ID
            String resourceId = extractResourceIdFromPath(request);

            // 如果URL不含资源ID，直接放行
            if (StringUtils.isBlank(resourceId)) {
                filterChain.doFilter(request, response);

                return;
            }

            // 获取资源的组织标签
            ResourceInfo resourceInfo = getResourceInfo(resourceId);

            // 如果是分片上传并且资源未找到(首次上传)，允许请求通过
            if (isChunkUpload && Objects.isNull(resourceInfo)) {
                filterChain.doFilter(request, response);

                return;
            }

            // 如果资源未找到，返回404
            if (Objects.isNull(resourceInfo)) {
                responseResultWriter.write(response, HttpServletResponse.SC_NOT_FOUND, "资源不存在或已被删除");

                return;
            }

            String resourceOrgTag = resourceInfo.getOrgTag();

            // 如果是公开资源、资源没有组织标签、或属于默认组织，直接放行
            if (resourceInfo.isPublic() || StringUtils.isBlank(resourceOrgTag)
                || DEFAULT_ORG_TAG.equals(resourceOrgTag)) {
                filterChain.doFilter(request, response);

                return;
            }

            // 从请求头获取token
            String token = jwtUtils.extractToken(request);
            if (StringUtils.isBlank(token)) {
                responseResultWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "未认证或登录已失效，请重新登录");

                return;
            }

            // 获取用户名和角色
            String username = jwtUtils.extractUsernameFromToken(token);
            String role = jwtUtils.extractRoleFromToken(token);

            // 如果是资源拥有者，直接放行
            if (StringUtils.isNotBlank(username) && username.equals(resourceInfo.getOwner())) {
                filterChain.doFilter(request, response);

                return;
            }

            // 如果是管理员，直接放行
            if ("ADMIN".equals(role)) {
                filterChain.doFilter(request, response);

                return;
            }

            // 检查是否为私人组织标签资源
            if (resourceOrgTag.startsWith(PRIVATE_TAG_PREFIX)) {
                // 私人标签资源只允许拥有者访问，此处已排除拥有者和管理员，拒绝访问
                responseResultWriter.write(response, HttpServletResponse.SC_FORBIDDEN, "无权限访问该资源");

                return;
            }

            // 获取用户的组织标签
            String userOrgTags = jwtUtils.extractOrgTagsFromToken(token);
            if (StringUtils.isBlank(userOrgTags)) {
                responseResultWriter.write(response, HttpServletResponse.SC_FORBIDDEN, "无权限访问该资源");

                return;
            }

            // 检查用户是否有权限访问该资源
            if (isUserAuthorized(userOrgTags, resourceOrgTag)) {
                filterChain.doFilter(request, response);
            } else {
                responseResultWriter.write(response, HttpServletResponse.SC_FORBIDDEN, "无权限访问该资源");
            }
        } catch (Exception e) {
            log.warn("组织标签授权过滤器发生错误: {}", e.getMessage(), e);

            responseResultWriter.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试");
        }
    }

    /**
     * 判断用户组织标签集合是否包含资源的组织标签
     *
     * @param userOrgTags    用户组织标签（逗号分隔）
     * @param resourceOrgTag 资源组织标签
     * @return true 表示用户具备访问该资源的标签
     */
    private boolean isUserAuthorized(String userOrgTags, String resourceOrgTag) {
        // 将用户的组织标签字符串转换为集合
        Set<String> userTags = Arrays.stream(userOrgTags.split(",")).collect(Collectors.toSet());

        // 检查用户的组织标签是否包含资源的组织标签
        return userTags.contains(resourceOrgTag);
    }

    /**
     * 根据资源ID查询资源信息（拥有者、组织标签、是否公开），当前仅支持文件上传表
     *
     * @param resourceId 资源ID（fileMd5）
     * @return 资源信息，未找到时返回 null
     */
    private ResourceInfo getResourceInfo(String resourceId) {
        if (StringUtils.isBlank(resourceId)) {
            return null;
        }

        // 尝试从文件上传表中获取资源信息
        Optional<FileUpload> fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(resourceId);
        if (fileUpload.isPresent()) {
            FileUpload file = fileUpload.get();
            ResourceInfo info = new ResourceInfo(file.getUserId(), file.getOrgTag(), file.isPublic());

            return info;
        } else {
            log.warn("在文件上传表中未找到资源 => 资源ID: {}", resourceId);
        }

        // TODO: 如果需要支持其他类型的资源，可以在这里添加查询逻辑

        // 如果未找到资源，返回null
        return null;
    }

    /**
     * 从请求路径中提取资源ID，兼容文件、文档（MD5/数字ID）、上传分片（请求头 X-File-MD5）、
     * 知识库等多种资源路径格式
     *
     * @param request 当前请求
     * @return 资源ID，路径不含资源ID时返回 null
     */
    private String extractResourceIdFromPath(HttpServletRequest request) {
        String path = request.getRequestURI();

        // 提取不同类型资源的ID
        // 1. 文件资源: /api/v1/files/{fileMd5}
        if (path.matches(".*/files/[^/]+.*")) {
            String fileId = path.replaceAll(".*/files/([^/]+).*", "$1");

            return fileId;
        }

        // 2. 文档删除资源: /api/v1/documents/{file_md5}
        if (path.matches(".*/documents/[a-fA-F0-9]{32}.*")) {
            String fileMd5 = path.replaceAll(".*/documents/([a-fA-F0-9]{32}).*", "$1");

            return fileMd5;
        }

        // 3. 文档资源: /api/v1/documents/{docId} (数字ID)
        if (path.matches(".*/documents/\\d+.*")) {
            String docId = path.replaceAll(".*/documents/(\\d+).*", "$1");

            return docId;
        }

        // 4. 上传分片: /api/v1/upload/chunk
        if (path.matches(".*/upload/chunk.*")) {
            String fileMd5 = request.getHeader("X-File-MD5");

            return fileMd5;
        }

        // 5. 知识库资源: /api/v1/knowledge/{resourceId}
        if (path.matches(".*/knowledge/[^/]+.*")) {
            String knowledgeId = path.replaceAll(".*/knowledge/([^/]+).*", "$1");
            return knowledgeId;
        }

        return null;
    }
}
