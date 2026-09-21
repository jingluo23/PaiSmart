package com.jingluo.paismart.utils;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 16:36
 * @Desc: 日志工具类，基于 SLF4J MDC 封装请求上下文（requestId/userId/sessionId）的写入与清理，
 *        并提供业务操作、API 调用、异常、性能等结构化日志的输出入口
 */
public class LogUtils {

    /**
     * MDC key：当前登录用户ID
     */
    public static final String USER_ID = "userId";

    /**
     * MDC key：请求唯一标识
     */
    public static final String REQUEST_ID = "requestId";

    /**
     * MDC key：会话ID
     */
    public static final String SESSION_ID = "sessionId";

    /**
     * MDC key：当前操作类型
     */
    public static final String OPERATION = "operation";

    /**
     * 设置请求级别的日志上下文：请求ID必填，用户ID与会话ID非空时才写入
     *
     * @param requestId 请求唯一标识
     * @param userId    用户ID，可为空
     * @param sessionId 会话ID，可为空
     */
    public static void setRequestContext(String requestId, String userId, String sessionId) {
        MDC.put(REQUEST_ID, requestId);
        if (StringUtils.isNotBlank(userId)) {
            MDC.put(USER_ID, userId);
        }

        if (StringUtils.isNotBlank(sessionId)) {
            MDC.put(SESSION_ID, sessionId);
        }
    }

    /**
     * 记录业务操作日志：将操作类型与用户ID写入 MDC 后输出格式化日志
     *
     * @param operation 操作类型
     * @param userId    用户ID
     * @param message   日志消息模板
     * @param args      消息占位符参数
     */
    public static void logBusiness(String operation, String userId, String message, Object... args) {
        try {
            MDC.put(OPERATION, operation);
            MDC.put(USER_ID, userId);
        } finally {
            MDC.clear();
        }
    }

    /**
     * 记录 API 调用日志：将操作类型标记为 API_CALL 并关联用户ID
     *
     * @param method     HTTP 方法
     * @param path       请求路径
     * @param userId     用户ID
     * @param statusCode 响应状态码
     * @param duration   请求耗时（毫秒）
     */
    public static void logApiCall(String method, String path, String userId, int statusCode, long duration) {
        try {
            MDC.put(USER_ID, userId);
            MDC.put(OPERATION, "API_CALL");
        } finally {
            MDC.clear();
        }
    }

    /**
     * 记录业务异常日志：将操作类型与用户ID写入 MDC 后输出异常堆栈
     *
     * @param operation 操作类型
     * @param userId    用户ID
     * @param message   日志消息模板
     * @param throwable 异常对象
     * @param args      消息占位符参数
     */
    public static void logBusinessError(String operation, String userId, String message, Throwable throwable,
        Object... args) {
        try {
            MDC.put(OPERATION, operation);
            MDC.put(USER_ID, userId);
        } finally {
            MDC.clear();
        }
    }

    /**
     * 记录性能日志：将操作类型写入 MDC 后输出耗时与详情
     *
     * @param operation 操作类型
     * @param duration  耗时（毫秒）
     * @param details   补充详情
     */
    public static void logPerformance(String operation, long duration, String details) {
        try {
            MDC.put(OPERATION, operation);
        } finally {
            MDC.clear();
        }
    }

    /**
     * 清理全部 MDC 上下文，须在请求结束（afterCompletion）时调用，防止线程池复用导致日志串号
     */
    public static void clearRequestContext() {
        MDC.clear();
    }
}
