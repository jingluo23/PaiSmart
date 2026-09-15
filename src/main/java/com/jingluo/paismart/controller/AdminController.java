package com.jingluo.paismart.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.jingluo.paismart.domain.request.AdminUserRequest;
import com.jingluo.paismart.domain.request.CreateInviteCodeRequest;
import com.jingluo.paismart.domain.request.ProviderConnectionTestRequest;
import com.jingluo.paismart.domain.request.UpdateScopeRequest;
import com.jingluo.paismart.domain.response.ResponseResult;
import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.UserRepository;
import com.jingluo.paismart.service.InviteCodeService;
import com.jingluo.paismart.service.ModelProviderConfigService;
import com.jingluo.paismart.service.RateLimitConfigService;
import com.jingluo.paismart.service.UsageDashboardService;
import com.jingluo.paismart.service.UserService;
import com.jingluo.paismart.utils.JwtUtils;

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
}
