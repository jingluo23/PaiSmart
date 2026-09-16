package com.jingluo.paismart.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.jingluo.paismart.domain.request.AddUserTokenRequest;
import com.jingluo.paismart.domain.request.AdminUserRequest;
import com.jingluo.paismart.domain.request.AssignOrgTagsRequest;
import com.jingluo.paismart.domain.request.CreateInviteCodeRequest;
import com.jingluo.paismart.domain.request.OrgTagRequest;
import com.jingluo.paismart.domain.request.OrgTagUpdateRequest;
import com.jingluo.paismart.domain.request.ProviderConnectionTestRequest;
import com.jingluo.paismart.domain.request.UpdateInviteCodeRequest;
import com.jingluo.paismart.domain.request.UpdateScopeRequest;
import com.jingluo.paismart.domain.response.MigrationReport;
import com.jingluo.paismart.domain.response.ResponseResult;
import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.OrganizationTag;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.OrganizationTagRepository;
import com.jingluo.paismart.repository.UserRepository;
import com.jingluo.paismart.service.ConversationService;
import com.jingluo.paismart.service.InviteCodeService;
import com.jingluo.paismart.service.ModelProviderConfigService;
import com.jingluo.paismart.service.RateLimitConfigService;
import com.jingluo.paismart.service.UsageDashboardService;
import com.jingluo.paismart.service.UsageQuotaService;
import com.jingluo.paismart.service.UserService;
import com.jingluo.paismart.service.UserTokenService;
import com.jingluo.paismart.utils.JwtUtils;
import com.jingluo.paismart.utils.MinioMigrationUtil;

import io.micrometer.common.util.StringUtils;

/**
 * @author 鲸落
 * @date 2026/9/13 16:21
 * @Description 管理员控制器，提供管理知识库、查看系统状态和监控用户活动的接口
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UsageDashboardService usageDashboardService;

    @Autowired
    private RateLimitConfigService rateLimitConfigService;

    @Autowired
    private ModelProviderConfigService modelProviderConfigService;

    @Autowired
    private UserService userService;

    @Autowired
    private InviteCodeService inviteCodeService;

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private UserTokenService userTokenService;

    @Autowired
    private UsageQuotaService usageQuotaService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private MinioMigrationUtil minioMigrationUtil;

    /**
     * 获取所有用户列表
     *
     * @param token
     * @return
     */
    @GetMapping("/users")
    public ResponseResult getAllUsers(@RequestHeader("Authorization") String token) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUserName = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUserName);

        List<User> users = userRepository.findAll();
        // 移除敏感信息
        users.forEach(user -> user.setPassword(null));

        return ResponseResult.success(users);
    }

    /**
     * 验证管理员
     *
     * @param adminUserName
     */
    private void validateAdmin(String adminUserName) {
        if (StringUtils.isBlank(adminUserName)) {
            throw new CustomException("token已失效，请重新登录", HttpStatus.UNAUTHORIZED);
        }

        User admin = userRepository.findByUsername(adminUserName)
            .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        if (Role.ADMIN != admin.getRole()) {
            throw new CustomException("无权限访问，需要管理员权限", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 添加知识库文档
     *
     * @param token
     * @param file
     * @param description
     * @return
     */
    @PostMapping("/knowledge/add")
    public ResponseResult addKnowledgeDocument(@RequestHeader("Authorization") String token,
        @RequestParam("file") MultipartFile file, @RequestParam("description") String description) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        // 这里应该调用知识库管理服务来处理文档
        // knowledgeService.addDocument(file, description);

        return ResponseResult.success("文档已成功添加到知识库");
    }

    /**
     * 删除知识库文档
     * 
     * @param token
     * @param documentId
     * @return
     */
    @DeleteMapping("/knowledge/{documentId}")
    public ResponseResult deleteKnowledgeDocument(@RequestHeader("Authorization") String token,
        @PathVariable("documentId") String documentId) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        // 这里应该调用知识库管理服务来删除文档
        // knowledgeService.deleteDocument(documentId);

        return ResponseResult.success("文档已成功从知识库中删除");
    }

    /**
     * 获取系统状态
     *
     * @param token
     * @return
     */
    @GetMapping("/system/status")
    public ResponseResult getSystemStatus(@RequestHeader("Authorization") String token) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        // 这里应该调用系统监控服务来获取系统状态
        // SystemStatus status = monitoringService.getSystemStatus();

        // 模拟系统状态数据
        Map<String, Object> status = new HashMap<>();
        status.put("cpu_usage", "30%");
        status.put("memory_usage", "45%");
        status.put("disk_usage", "60%");
        status.put("active_users", 15);
        status.put("total_documents", 250);
        status.put("total_conversations", 1200);

        return ResponseResult.success(status);
    }

    /**
     * 获取用户活动日志
     * 
     * @param token
     * @param username
     * @param start_date
     * @param end_date
     * @return
     */
    @GetMapping("/user-activities")
    public ResponseResult getUserActivities(@RequestHeader("Authorization") String token,
        @RequestParam(required = false) String username, @RequestParam(required = false) String start_date,
        @RequestParam(required = false) String end_date) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        // 这里应该调用用户活动监控服务来获取活动日志
        // List<UserActivity> activities = activityService.getUserActivities(username, startDate, endDate);

        // 模拟用户活动数据
        List<Map<String, Object>> activities = List.of(
            Map.of("username", "user1", "action", "LOGIN", "timestamp", "2026-09-14T10:15:30", "ip_address",
                "192.168.1.100"),
            Map.of("username", "user2", "action", "UPLOAD_FILE", "timestamp", "2026-09-14T11:20:45", "ip_address",
                "192.168.1.101"));

        return ResponseResult.success(activities);
    }

    /**
     * 获取用量概览
     *
     * @param token
     * @param days
     * @return
     */
    @GetMapping("/usage/overview")
    public ResponseResult getUsageOverview(@RequestHeader("Authorization") String token,
        @RequestParam(defaultValue = "7") int days) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        return ResponseResult.success(usageDashboardService.buildOverview(days));
    }

    /**
     * 获取速率限制配置
     *
     * @param token
     * @return
     */
    @GetMapping("/rate-limits")
    public ResponseResult getRateLimits(@RequestHeader("Authorization") String token) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        return ResponseResult.success(rateLimitConfigService.getCurrentSettings());
    }

    /**
     * 获取模型提供者配置
     *
     * @param token
     * @return
     */
    @GetMapping("/model-providers")
    public ResponseResult getModelProviders(@RequestHeader("Authorization") String token) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        return ResponseResult.success(modelProviderConfigService.getCurrentSettings());
    }

    /**
     * 更新模型提供者配置
     *
     * @param token
     * @param scope
     * @param request
     * @return
     */
    @PutMapping("/model-providers/{scope}")
    public ResponseResult updateModelProviders(@RequestHeader("Authorization") String token, @PathVariable String scope,
        @RequestBody @Validated UpdateScopeRequest request) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        return ResponseResult.success(modelProviderConfigService.updateScope(scope, request, adminUsername));
    }

    /**
     * 测试模型提供者连通性
     *
     * @param token
     *            管理员登录凭证
     * @param scope
     *            模型作用域（llm / embedding）
     * @param request
     *            连接测试参数（API 地址、模型、密钥等）
     * @return 连通性测试结果（是否成功、结果描述、耗时）
     */
    @PostMapping("/model-providers/{scope}/test")
    public ResponseResult testModelProviderConnection(@RequestHeader("Authorization") String token,
        @PathVariable String scope, @RequestBody ProviderConnectionTestRequest request) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        return ResponseResult.success(modelProviderConfigService.testConnection(scope, request));
    }

    /**
     * 创建管理员用户
     *
     * @param token
     *            管理员登录凭证
     * @param request
     *            创建管理员请求参数（用户名、密码）
     * @return 创建结果提示
     */
    @PostMapping("/users/create-admin")
    public ResponseResult createAdminUser(@RequestHeader("Authorization") String token,
        @RequestBody AdminUserRequest request) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        userService.createAdminUser(request.getUsername(), request.getPassword(), adminUsername);

        return ResponseResult.success("管理员用户创建成功");
    }

    /**
     * 创建邀请码
     *
     * @param token
     *            管理员登录凭证
     * @param request
     *            创建邀请码请求参数（自定义邀请码、最大可用次数、批量数量）
     * @return 创建成功的邀请码列表
     */
    @PostMapping("/invite-codes")
    public ResponseResult createInviteCode(@RequestHeader("Authorization") String token,
        @RequestBody CreateInviteCodeRequest request) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        var created = inviteCodeService.createInviteCodes(adminUsername, request.getCode(), request.getMaxUses(), null,
            request.getCount());

        return ResponseResult.success(created);
    }

    /**
     * 分页查询邀请码列表
     *
     * @param token
     *            管理员登录凭证
     * @param enabled
     *            启用状态筛选（可选，为空表示查询全部）
     * @param page
     *            页码（默认 1）
     * @param size
     *            每页数量（默认 20）
     * @return 邀请码分页列表
     */
    @GetMapping("/invite-codes")
    public ResponseResult listInviteCodes(@RequestHeader("Authorization") String token,
        @RequestParam(required = false) Boolean enabled, @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        return ResponseResult.success(inviteCodeService.list(enabled, page, size));
    }

    /**
     * 禁用邀请码
     *
     * @param token
     *            管理员登录凭证
     * @param id
     *            邀请码 ID
     * @return 禁用结果提示
     */
    @PatchMapping("/invite-codes/{id}/disable")
    public ResponseResult disableInviteCode(@RequestHeader("Authorization") String token, @PathVariable Long id) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        inviteCodeService.disable(id, adminUsername);

        return ResponseResult.success("邀请码已禁用");
    }

    /**
     * 删除邀请码
     *
     * @param token
     *            管理员登录凭证
     * @param id
     *            邀请码 ID
     * @return 删除结果提示
     */
    @DeleteMapping("/invite-codes/{id}")
    public ResponseResult deleteInviteCode(@RequestHeader("Authorization") String token, @PathVariable Long id) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        inviteCodeService.delete(id, adminUsername);

        return ResponseResult.success("邀请码已删除");
    }

    /**
     * 编辑邀请码
     *
     * @param token
     *            管理员登录凭证
     * @param id
     *            邀请码 ID
     * @param request
     *            更新邀请码请求参数（新的邀请码字符串、最大可用次数）
     * @return 更新后的邀请码
     */
    @PutMapping("/invite-codes/{id}")
    public ResponseResult updateInviteCode(@RequestHeader("Authorization") String token, @PathVariable Long id,
        @RequestBody UpdateInviteCodeRequest request) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        var updated = inviteCodeService.update(id, adminUsername, request.getCode(), request.getMaxUses(), null);

        return ResponseResult.success(updated);
    }

    /**
     * 创建组织标签
     *
     * @param token
     * @param request
     * @return
     */
    @PostMapping("/org-tags")
    public ResponseResult createOrganizationTag(@RequestHeader("Authorization") String token,
        @RequestBody OrgTagRequest request) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        OrganizationTag tag = userService.createOrganizationTag(request.getTagId(), request.getName(),
            request.getDescription(), request.getParentTag(), request.getUploadMaxSizeMb(), adminUsername);

        return ResponseResult.success(tag);
    }

    /**
     * 获取所有组织标签列表
     *
     * @param token
     * @return
     */
    @GetMapping("/org-tags")
    public ResponseResult getAllOrganizationTags(@RequestHeader("Authorization") String token) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        List<OrganizationTag> tags = organizationTagRepository.findAll();

        return ResponseResult.success(tags);
    }

    /**
     * 为用户分配组织标签
     *
     * @param token
     * @param userId
     * @param request
     * @return
     */
    @PutMapping("/users/{userId}/org-tags")
    public ResponseResult assignOrgTagsToUser(@RequestHeader("Authorization") String token, @PathVariable Long userId,
        @RequestBody AssignOrgTagsRequest request) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        userService.assignOrgTagsToUser(userId, request.getOrgTags(), adminUsername);

        return ResponseResult.success("组织标签分配成功");
    }

    /**
     * 获取组织标签树形结构，传入 page 或 size 时对根节点分页返回
     *
     * @param token
     * @param page
     * @param size
     * @return
     */
    @GetMapping("/org-tags/tree")
    public ResponseResult getOrganizationTagTree(@RequestHeader("Authorization") String token,
        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        List<Map<String, Object>> tagTree = userService.getOrganizationTagTree();

        Object data = (Objects.nonNull(page) || Objects.nonNull(size)) ? paginateTree(tagTree, page, size) : tagTree;

        return ResponseResult.success(data);
    }

    /**
     * 对标签树根节点做内存分页，返回结构与分页查询结果保持一致（data/content 双字段兼容前端）
     *
     * @param tagTree
     * @param page
     * @param size
     * @return
     */
    private Map<String, Object> paginateTree(List<Map<String, Object>> tagTree, Integer page, Integer size) {
        int pageNumber = Objects.isNull(page) || page < 1 ? 1 : page;

        int pageSize = Objects.isNull(size) || size < 1 ? 10 : size;

        int total = tagTree.size();

        int fromIndex = Math.min((pageNumber - 1) * pageSize, total);

        int toIndex = Math.min(fromIndex + pageSize, total);

        List<Map<String, Object>> pagedTree = tagTree.subList(fromIndex, toIndex);

        Map<String, Object> result = new HashMap<>();
        result.put("data", pagedTree);
        result.put("content", pagedTree);
        result.put("number", pageNumber);
        result.put("size", pageSize);
        result.put("totalElements", total);

        return result;
    }

    /**
     * 更新组织标签
     *
     * @param token
     * @param tagId
     * @param request
     * @return
     */
    @PutMapping("/org-tags/{tagId}")
    public ResponseResult updateOrganizationTag(@RequestHeader("Authorization") String token,
        @PathVariable String tagId, @RequestBody OrgTagUpdateRequest request) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        OrganizationTag updatedTag = userService.updateOrganizationTag(tagId, request.getName(),
            request.getDescription(), request.getParentTag(), request.getUploadMaxSizeMb(), adminUsername);

        return ResponseResult.success(updatedTag);
    }

    /**
     * 删除组织标签
     *
     * @param token
     * @param tagId
     * @return
     */
    @DeleteMapping("/org-tags/{tagId}")
    public ResponseResult deleteOrganizationTag(@RequestHeader("Authorization") String token,
        @PathVariable String tagId) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        userService.deleteOrganizationTag(tagId, adminUsername);

        return ResponseResult.success("组织标签删除成功");
    }

    /**
     * 分页查询用户列表，支持按用户名关键词、组织标签、状态筛选
     *
     * @param token
     * @param keyword
     * @param orgTag
     * @param status
     * @param page
     * @param size
     * @return
     */
    @GetMapping("/users/list")
    public ResponseResult getUserList(@RequestHeader("Authorization") String token,
        @RequestParam(required = false) String keyword, @RequestParam(required = false) String orgTag,
        @RequestParam(required = false) Integer status, @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        Map<String, Object> usersData = userService.getUserList(keyword, orgTag, status, page, size);

        return ResponseResult.success(usersData);
    }

    /**
     * 管理员为指定用户追加 Token 额度（LLM / Embedding 可同时追加至少一种）
     *
     * @param token
     *            管理员登录凭证
     * @param userId
     *            目标用户 ID
     * @param request
     *            追加 Token 请求参数（LLM 数量、Embedding 数量、追加原因）
     * @return 目标用户信息及追加后的用量快照
     */
    @PostMapping("/users/{userId}/tokens/add")
    public ResponseResult addUserTokens(@RequestHeader("Authorization") String token, @PathVariable Long userId,
        @RequestBody AddUserTokenRequest request) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        User targetUser =
            userRepository.findById(userId).orElseThrow(() -> new CustomException("目标用户不存在", HttpStatus.NOT_FOUND));

        long llmToken = Objects.isNull(request.getLlmToken()) ? 0L : request.getLlmToken();

        long embeddingToken = Objects.isNull(request.getEmbeddingToken()) ? 0L : request.getEmbeddingToken();

        if (llmToken < 0 || embeddingToken < 0) {
            throw new CustomException("追加 Token 数量不能为负数", HttpStatus.BAD_REQUEST);
        }

        if (llmToken == 0 && embeddingToken == 0) {
            throw new CustomException("请至少追加一种 Token 额度", HttpStatus.BAD_REQUEST);
        }

        String userIdText = String.valueOf(userId);
        String reason = normalizeManualTokenReason(request.getReason());
        String remark = "admin=" + adminUsername;

        if (llmToken > 0) {
            userTokenService.addLlmTokens(userIdText, llmToken, reason, remark);
        }

        if (embeddingToken > 0) {
            userTokenService.addEmbeddingTokens(userIdText, embeddingToken, reason, remark);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("username", targetUser.getUsername());
        data.put("usage", usageQuotaService.getSnapshot(userIdText));

        return ResponseResult.success(data);
    }

    /**
     * 规范化手动追加 Token 的原因描述，为空时使用默认文案，超长时截断至 200 字符
     *
     * @param reason
     *            追加原因（可选）
     * @return 规范化后的原因描述
     */
    private String normalizeManualTokenReason(String reason) {
        if (StringUtils.isBlank(reason)) {
            return "管理员手动追加";
        }

        String trimmed = reason.trim();

        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    /**
     * 获取对话记录，支持按用户ID和时间范围过滤
     *
     * @param token
     *            管理员登录令牌
     * @param userid
     *            目标用户ID，为空时查询所有用户的对话
     * @param start_date
     *            开始时间，支持秒级、分钟级、小时级、日期级格式
     * @param end_date
     *            结束时间，格式同开始时间，解析时自动补齐到对应时间粒度的末秒
     * @return 按时间正序排列的对话消息列表
     */
    @GetMapping("/conversation")
    public ResponseResult getAllConversations(@RequestHeader("Authorization") String token,
        @RequestParam(required = false) String userid, @RequestParam(required = false) String start_date,
        @RequestParam(required = false) String end_date) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        String targetUsername = null;
        if (StringUtils.isNotBlank(userid)) {
            try {
                Long userIdLong = Long.parseLong(userid);
                Optional<User> targetUser = userRepository.findById(userIdLong);
                if (targetUser.isPresent()) {
                    targetUsername = targetUser.get().getUsername();
                } else {
                    return ResponseResult.fail(HttpStatus.NOT_FOUND.value(), "目标用户不存在");
                }
            } catch (NumberFormatException e) {
                return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), "无效的用户ID格式");
            }
        }

        LocalDateTime startDateTime = parseStartDate(start_date);

        LocalDateTime endDateTime = parseEndDate(end_date);

        List<Map<String, Object>> allConversations = conversationService.toMessageHistory(
            conversationService.getAllConversations(adminUsername, targetUsername, startDateTime, endDateTime), true);

        return ResponseResult.success(allConversations);
    }

    /**
     * 解析结束时间字符串，支持多种精度格式，并自动补齐到对应时间粒度的末秒：
     * 日期级取当天 23:59:59，小时级取该小时 59 分 59 秒，分钟级取该分钟 59 秒
     *
     * @param dateTimeStr
     *            结束时间字符串，支持 yyyy-MM-dd、yyyy-MM-ddTHH、yyyy-MM-ddTHH:mm、yyyy-MM-ddTHH:mm:ss 格式
     * @return 解析后的结束时间，入参为空时返回 null
     */
    private LocalDateTime parseEndDate(String dateTimeStr) {
        if (StringUtils.isBlank(dateTimeStr) || dateTimeStr.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (java.time.format.DateTimeParseException e1) {
            try {
                if (dateTimeStr.length() == 16) {
                    return LocalDateTime.parse(dateTimeStr + ":59");
                }

                if (dateTimeStr.length() == 13) {
                    return LocalDateTime.parse(dateTimeStr + ":59:59");
                }

                if (dateTimeStr.length() == 10) {
                    return LocalDate.parse(dateTimeStr).plusDays(1).atStartOfDay().minusSeconds(1);
                }
            } catch (Exception e2) {
                throw new CustomException("无效的结束时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
            }
        }

        throw new CustomException("无效的结束时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
    }

    /**
     * 解析起始时间字符串，支持多种精度格式，并自动补齐到对应时间粒度的起始时刻：
     * 日期级取当天 00:00:00，小时级取该小时 00 分 00 秒，分钟级取该分钟 00 秒
     *
     * @param dateTimeStr
     *            起始时间字符串，支持 yyyy-MM-dd、yyyy-MM-ddTHH、yyyy-MM-ddTHH:mm、yyyy-MM-ddTHH:mm:ss 格式
     * @return 解析后的起始时间，入参为空时返回 null
     */
    private LocalDateTime parseStartDate(String dateTimeStr) {
        if (StringUtils.isBlank(dateTimeStr) || dateTimeStr.trim().isEmpty()) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateTimeStr);
        } catch (java.time.format.DateTimeParseException e1) {
            try {
                if (dateTimeStr.length() == 16) {
                    return LocalDateTime.parse(dateTimeStr + ":00");
                }

                if (dateTimeStr.length() == 13) {
                    return LocalDateTime.parse(dateTimeStr + ":00:00");
                }

                if (dateTimeStr.length() == 10) {
                    return LocalDate.parse(dateTimeStr).atStartOfDay();
                }
            } catch (Exception e2) {
                throw new CustomException("无效的起始时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
            }
        }

        throw new CustomException("无效的起始时间格式: " + dateTimeStr, HttpStatus.BAD_REQUEST);
    }

    /**
     * 触发 MinIO 文件迁移，将按文件名存储的历史对象迁移为按文件 MD5 存储
     *
     * @param token
     *            管理员登录令牌
     * @param adminKey
     *            管理员密钥，用于二次确认
     * @return 迁移结果报告，包含成功、跳过、失败数量及错误明细
     */
    @PostMapping("/migrate-minio")
    public ResponseResult migrateMinioFiles(@RequestHeader("Authorization") String token,
        @RequestParam String adminKey) {
        if (StringUtils.isBlank(token)) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "token不能为空，请重新登录");
        }

        String adminUsername = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));

        validateAdmin(adminUsername);

        // 简单密钥验证
        if (!"migration2024".equals(adminKey)) {
            return ResponseResult.fail(HttpStatus.FORBIDDEN.value(), "无效的管理员密钥");
        }

        MigrationReport report = minioMigrationUtil.migrateAllFiles();

        return ResponseResult.success(report);
    }
}
