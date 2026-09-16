package com.jingluo.paismart.exception;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 16:14
 * @Desc: 限流异常，触发接口访问频率限制时抛出，携带建议的等待秒数
 */
public class RateLimitExceededException extends RuntimeException {

    /**
     * 建议客户端等待的秒数（即限流窗口剩余时间），用于设置 Retry-After 响应头
     */
    private final long retryAfterSeconds;

    /**
     * 创建限流异常
     *
     * @param message
     *            异常提示信息
     * @param retryAfterSeconds
     *            建议等待的秒数
     */
    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 获取建议等待的秒数
     *
     * @return 限流窗口剩余秒数
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
