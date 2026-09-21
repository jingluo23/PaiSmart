package com.jingluo.paismart.config;

import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.jingluo.paismart.utils.JwtUtils;
import com.jingluo.paismart.utils.LogUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 16:31
 * @Desc: 请求日志拦截器，在处理前后埋点：请求开始时设置 requestId/userId 等 MDC 上下文，
 *        请求结束时统计耗时并记录 API 调用、异常与慢请求（>3s）日志
 */
@Component
public class LoggingInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtils jwtUtils;

    /**
     * 请求开始时间在 request 属性中的 key
     */
    private static final String START_TIME_ATTRIBUTE = "startTime";

    /**
     * 请求ID在 request 属性中的 key
     */
    private static final String REQUEST_ID_ATTRIBUTE = "requestId";

    /**
     * 请求前置处理：记录开始时间、生成请求ID、初始化 MDC 日志上下文并输出请求开始日志
     *
     * @return 恒为 true，继续执行后续拦截器与控制器
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 记录请求开始时间
        long startTime = System.currentTimeMillis();
        request.setAttribute(START_TIME_ATTRIBUTE, startTime);

        // 生成请求ID
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);

        // 获取用户信息
        String userId = extractUserId(request);
        String sessionId = request.getSession(false) != null ? request.getSession().getId() : null;

        // 设置请求上下文
        LogUtils.setRequestContext(requestId, userId, sessionId);

        // 记录请求开始日志（仅对API请求）
        String path = request.getRequestURI();
        if (isApiRequest(path)) {
            LogUtils.logBusiness("REQUEST_START", userId, "开始处理请求 [%s] %s", request.getMethod(), path);
        }

        return true;
    }

    /**
     * 判断是否为需要记录业务日志的 API 请求（/api/ 或 /chat/ 前缀）
     *
     * @param path 请求路径
     * @return true 表示 API 请求
     */
    private boolean isApiRequest(String path) {
        return path.startsWith("/api/") || path.startsWith("/chat/");
    }

    /**
     * 从请求头 Token 中提取用户ID，解析失败或未携带 Token 时返回 anonymous
     *
     * @param request 当前请求
     * @return 用户ID或 anonymous
     */
    private String extractUserId(HttpServletRequest request) {
        try {
            String token = jwtUtils.extractToken(request);
            if (StringUtils.isNotBlank(token)) {
                return jwtUtils.extractUserIdFromToken(token);
            }
        } catch (Exception e) {
            // 忽略token解析异常
        }

        return "anonymous";
    }

    /**
     * 请求完成后的收尾处理：计算耗时并记录 API 调用日志、处理异常与慢请求，
     * 最后清理 MDC 上下文，防止线程复用导致日志串号
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
        Exception ex) {
        try {
            // 计算请求耗时
            Long startTime = (Long)request.getAttribute(START_TIME_ATTRIBUTE);
            if (startTime != null) {
                long duration = System.currentTimeMillis() - startTime;
                String userId = extractUserId(request);
                String path = request.getRequestURI();

                // 记录API调用日志（仅对API请求）
                if (isApiRequest(path)) {
                    LogUtils.logApiCall(request.getMethod(), path, userId, response.getStatus(), duration);

                    // 记录异常信息
                    if (ex != null) {
                        LogUtils.logBusinessError("REQUEST_ERROR", userId, "请求处理异常 [%s] %s", ex, request.getMethod(),
                            path);
                    }

                    // 记录慢请求
                    if (duration > 3000) { // 超过3秒的请求
                        LogUtils.logPerformance("SLOW_REQUEST", duration,
                            String.format("[%s] %s [用户:%s]", request.getMethod(), path, userId));
                    }
                }
            }
        } finally {
            // 清除请求上下文
            LogUtils.clearRequestContext();
        }
    }
}
