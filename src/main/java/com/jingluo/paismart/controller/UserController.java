package com.jingluo.paismart.controller;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jingluo.paismart.domain.request.PrimaryOrgRequest;
import com.jingluo.paismart.domain.request.UserRequest;
import com.jingluo.paismart.domain.response.ResponseResult;
import com.jingluo.paismart.domain.response.UserTokenRecordDTO;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.UserRepository;
import com.jingluo.paismart.service.RateLimitService;
import com.jingluo.paismart.service.UsageQuotaService;
import com.jingluo.paismart.service.UserService;
import com.jingluo.paismart.service.UserTokenService;
import com.jingluo.paismart.utils.JwtUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 16:05
 * @Desc: 用户接口，提供注册、登录、个人信息查询、组织标签管理、令牌登出及 Token 流水查询等端点
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UsageQuotaService usageQuotaService;

    @Autowired
    private UserTokenService userTokenService;

    /**
     * 用户注册
     * <p>
     * 先按客户端 IP 做注册频率限制，再执行注册逻辑（含注册策略校验、邀请码消费）。
     *
     * @param request
     *            注册请求体（用户名、密码、邀请码）
     * @param httpServletRequest
     *            请求对象，用于解析客户端真实 IP
     * @return 注册结果
     */
    @PostMapping("/register")
    public ResponseResult<?> register(@RequestBody @Validated UserRequest request,
        HttpServletRequest httpServletRequest) {
        String clientIp = resolveClientIp(httpServletRequest);

        rateLimitService.checkRegisterByIp(clientIp);

        userService.registerUser(request.getUsername(), request.getPassword(), request.getInviteCode());

        return ResponseResult.success("用户注册成功");
    }

    /**
     * 解析客户端真实 IP
     * <p>
     * 依次尝试从 CDN / 反向代理的标准请求头中提取（CF-Connecting-IP、True-Client-IP、X-Forwarded-For、X-Real-IP 等）， 全部失效时回退到请求的远程地址。
     *
     * @param request
     *            请求对象
     * @return 客户端 IP，无法解析时返回 "unknown"
     */
    private String resolveClientIp(HttpServletRequest request) {
        return firstUsableIp(request.getHeader("CF-Connecting-IP"), request.getHeader("True-Client-IP"),
            extractForwardedForIp(request.getHeader("X-Forwarded-For")), request.getHeader("X-Real-IP"),
            request.getHeader("Proxy-Client-IP"), request.getHeader("WL-Proxy-Client-IP"), request.getRemoteAddr())
            .orElse("unknown");
    }

    /**
     * 从 X-Forwarded-For 头中提取第一个有效 IP
     * <p>
     * X-Forwarded-For 可能包含逗号分隔的多级代理 IP 链，逐段去除空白后取首个合法值。
     *
     * @param xForwardedFor
     *            X-Forwarded-For 请求头原始值
     * @return 第一个有效 IP，头为空或无合法值时返回 null
     */
    private String extractForwardedForIp(String xForwardedFor) {
        if (StringUtils.isBlank(xForwardedFor)) {
            return null;
        }

        return Arrays.stream(xForwardedFor.split(",")).map(String::trim).filter(this::isUsableIp).findFirst()
            .orElse(null);
    }

    /**
     * 返回候选 IP 列表中第一个可用的值
     *
     * @param candidates
     *            候选 IP 列表（按优先级排列）
     * @return 第一个非空且不为 "unknown" 的 IP
     */
    private Optional<String> firstUsableIp(String... candidates) {
        return Arrays.stream(candidates).filter(this::isUsableIp).findFirst();
    }

    /**
     * 判断 IP 值是否可用：非空白且不为 "unknown" 占位值
     *
     * @param ip
     *            待检查的 IP 值
     * @return true 表示可用
     */
    private boolean isUsableIp(String ip) {
        return StringUtils.isNotBlank(ip) && !"unknown".equalsIgnoreCase(ip);
    }

    /**
     * 用户登录
     * <p>
     * 先按客户端 IP 做登录频率限制，再校验用户名密码， 认证通过后签发访问令牌（token，1 小时）与刷新令牌（refreshToken，7 天）。
     *
     * @param request
     *            登录请求体（用户名、密码）
     * @param httpServletRequest
     *            请求对象，用于解析客户端真实 IP
     * @return 含 token 与 refreshToken 的登录结果，凭证无效时返回 401
     */
    @PostMapping("/login")
    public ResponseResult<?> login(@RequestBody @Validated UserRequest request, HttpServletRequest httpServletRequest) {
        String clientIp = resolveClientIp(httpServletRequest);

        rateLimitService.checkLoginByIp(clientIp);

        String username = userService.authenticateUser(request.getUsername(), request.getPassword());
        if (StringUtils.isBlank(username)) {
            return ResponseResult.fail(HttpStatus.UNAUTHORIZED.value(), "无效凭证");
        }

        String token = jwtUtils.generateToken(username);
        String refreshToken = jwtUtils.generateRefreshToken(username);

        Map<String, String> map = Map.of("token", token, "refreshToken", refreshToken);

        return ResponseResult.success(map);
    }

    /**
     * 查询当前登录用户信息
     * <p>
     * 从令牌中解析用户名并加载用户，返回除密码外的用户基本信息、组织标签及主组织。
     *
     * @param token
     *            Authorization 请求头中的 JWT 令牌
     * @return 当前用户信息
     */
    @GetMapping("/me")
    public ResponseResult<?> getCurrentUser(@RequestHeader("Authorization") String token) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (StringUtils.isBlank(username)) {
            throw new CustomException("无效凭证", HttpStatus.UNAUTHORIZED);
        }

        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new CustomException("未找到用户", HttpStatus.NOT_FOUND));

        // 手动构建返回对象，不包含 password 字段
        Map<String, Object> displayUserData = new LinkedHashMap<>();
        displayUserData.put("id", user.getId());
        displayUserData.put("username", user.getUsername());
        displayUserData.put("role", user.getRole());

        // 添加组织标签信息
        if (StringUtils.isNotBlank(user.getOrgTags())) {
            List<String> orgTagsList = Arrays.asList(user.getOrgTags().split(","));
            displayUserData.put("orgTags", orgTagsList);
        } else {
            displayUserData.put("orgTags", List.of());
        }

        // 添加主组织标签信息
        displayUserData.put("primaryOrg", user.getPrimaryOrg());

        displayUserData.put("createdAt", user.getCreatedAt());
        displayUserData.put("updatedAt", user.getUpdatedAt());

        // 返回响应
        return ResponseResult.success(displayUserData);
    }

    /**
     * 查询当前用户的组织标签信息
     * <p>
     * 返回用户的组织标签列表、主组织及各标签的详细信息（含上传大小限制）。
     *
     * @param token
     *            Authorization 请求头中的 JWT 令牌
     * @return 组织标签信息
     */
    @GetMapping("/org-tags")
    public ResponseResult<?> getUserOrgTags(@RequestHeader("Authorization") String token) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (StringUtils.isBlank(username)) {
            throw new CustomException("无效凭证", HttpStatus.UNAUTHORIZED);
        }

        Map<String, Object> orgTagsInfo = userService.getUserOrgTags(username);

        return ResponseResult.success(orgTagsInfo);
    }

    /**
     * 设置用户的主组织标签
     * <p>
     * 仅允许设置为已分配给当前用户的组织标签。
     *
     * @param token
     *            Authorization 请求头中的 JWT 令牌
     * @param request
     *            请求体，含主组织标签 ID
     * @return 设置结果
     */
    @PutMapping("/primary-org")
    public ResponseResult<?> setPrimaryOrg(@RequestHeader("Authorization") String token,
        @RequestBody @Validated PrimaryOrgRequest request) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (StringUtils.isBlank(username)) {
            throw new CustomException("无效凭证", HttpStatus.UNAUTHORIZED);
        }

        userService.setUserPrimaryOrg(username, request.getPrimaryOrg());

        return ResponseResult.success("设置主组织成功");
    }

    /**
     * 查询当前用户的 Token 用量快照
     *
     * @param token
     *            Authorization 请求头中的 JWT 令牌
     * @return 用户各类型 Token 的用量快照
     */
    @GetMapping("/usage")
    public ResponseResult<?> getCurrentUserUsage(@RequestHeader("Authorization") String token) {
        String username = jwtUtils.extractUsernameFromToken(token.replace("Bearer ", ""));
        if (StringUtils.isBlank(username)) {
            throw new CustomException("无效凭证", HttpStatus.UNAUTHORIZED);
        }

        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new CustomException("用户未找到", HttpStatus.NOT_FOUND));

        return ResponseResult.success(usageQuotaService.getSnapshot(String.valueOf(user.getId())));
    }

    /**
     * 查询用户可用的上传组织标签
     * <p>
     * 返回用户的组织标签列表与主组织，供上传文件时选择所属组织使用。
     *
     * @param userId
     *            请求属性中注入的用户 ID
     * @return 组织标签列表与主组织
     */
    @GetMapping("/upload-orgs")
    public ResponseResult<?> getUploadOrgTags(@RequestAttribute("userId") String userId) {
        // 获取用户所有组织标签
        List<String> orgTags = Arrays.asList(userService.getUserOrgTags(userId).get("orgTags").toString().split(","));
        // 获取用户主组织标签
        String primaryOrg = userService.getUserPrimaryOrg(userId);

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("orgTags", orgTags);
        responseData.put("primaryOrg", primaryOrg);

        return ResponseResult.success(responseData);
    }

    /**
     * 用户登出
     * <p>
     * 将当前请求携带的访问令牌加入黑名单并从缓存移除，使其立即失效，不影响其他设备。
     *
     * @param token
     *            Authorization 请求头中的 JWT 令牌
     * @return 登出结果
     */
    @PostMapping("/logout")
    public ResponseResult<?> logout(@RequestHeader("Authorization") String token) {
        if (StringUtils.isBlank(token) || !token.startsWith("Bearer ")) {
            return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), "令牌格式无效");
        }

        String jwtToken = token.replace("Bearer ", "");
        String username = jwtUtils.extractUsernameFromToken(jwtToken);

        if (StringUtils.isBlank(username)) {
            return ResponseResult.fail(HttpStatus.UNAUTHORIZED.value(), "无效凭证");
        }

        // 使当前token失效
        jwtUtils.invalidateToken(jwtToken);

        return ResponseResult.success("退出成功");
    }

    /**
     * 全端登出
     * <p>
     * 清除该用户在 Redis 中登记的所有令牌，使所有设备上的会话一并失效。
     *
     * @param token
     *            Authorization 请求头中的 JWT 令牌
     * @return 登出结果
     */
    @PostMapping("/logout-all")
    public ResponseResult<?> logoutAll(@RequestHeader("Authorization") String token) {
        if (StringUtils.isBlank(token) || !token.startsWith("Bearer ")) {
            return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), "令牌格式无效");
        }

        String jwtToken = token.replace("Bearer ", "");

        String username = jwtUtils.extractUsernameFromToken(jwtToken);

        String userId = jwtUtils.extractUserIdFromToken(jwtToken);

        if (StringUtils.isBlank(username) || StringUtils.isBlank(userId)) {
            return ResponseResult.fail(HttpStatus.UNAUTHORIZED.value(), "无效凭证");
        }

        // 使用户所有token失效
        jwtUtils.invalidateAllUserTokens(userId);

        return ResponseResult.success("已从所有设备成功登出");
    }

    /**
     * 分页查询当前用户的 Token 变动流水
     * <p>
     * 返回每笔变动的类型、数量、前后余额、原因及备注等信息，按创建时间倒序分页。
     *
     * @param token
     *            Authorization 请求头中的 JWT 令牌
     * @param page
     *            页码，从 0 开始，默认 0
     * @param size
     *            每页数量，默认 10
     * @return 分页的 Token 变动记录
     */
    @GetMapping("/token-records")
    public ResponseResult<?> getTokenRecords(@RequestHeader("Authorization") String token,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        if (StringUtils.isBlank(userId)) {
            throw new CustomException("Invalid token", HttpStatus.UNAUTHORIZED);
        }

        var records = userTokenService.getUserTokenRecords(userId, page, size);
        var recordPage =
            records.map(record -> UserTokenRecordDTO.builder().id(record.getId()).recordDate(record.getRecordDate())
                .tokenType(record.getTokenType().name()).changeType(record.getChangeType().name())
                .amount(record.getAmount()).balanceBefore(record.getBalanceBefore())
                .balanceAfter(record.getBalanceAfter()).reason(record.getReason()).remark(record.getRemark())
                .createdAt(record.getCreatedAt()).requestCount(record.getRequestCount()).build());

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("content", recordPage.getContent());
        responseData.put("totalElements", recordPage.getTotalElements());
        responseData.put("totalPages", recordPage.getTotalPages());
        responseData.put("number", recordPage.getNumber());
        responseData.put("size", recordPage.getSize());
        responseData.put("first", recordPage.isFirst());
        responseData.put("last", recordPage.isLast());
        responseData.put("empty", recordPage.isEmpty());

        return ResponseResult.success(responseData);
    }
}
