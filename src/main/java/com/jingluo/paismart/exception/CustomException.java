package com.jingluo.paismart.exception;

import org.springframework.http.HttpStatus;

/**
 * @author 鲸落
 * @date 2026/9/13 16:39
 * @Description 自定义异常类
 */
public class CustomException extends RuntimeException {

    /**
     * 状态码
     */
    private final HttpStatus status;

    /**
     * 构造方法
     *
     * @param message
     *            异常信息
     * @param status
     *            状态码
     */
    public CustomException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    /**
     * 获取状态码
     *
     * @return 状态码
     */
    public HttpStatus getStatus() {
        return status;
    }
}
