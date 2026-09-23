package com.jingluo.paismart.exception;

import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import com.jingluo.paismart.domain.response.ResponseResult;

import lombok.extern.slf4j.Slf4j;

/**
 * @date 2026/9/23
 * @Description 全局异常处理器：将各类异常统一转换为 ResponseResult 结构返回；
 *              HTTP 状态码保持 200，业务状态码由 ResponseResult.code 携带，由前端按 code 统一判断成败
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：按异常携带的 HttpStatus 作为业务码返回
     *
     * @param e
     *            业务异常
     * @return 响应结果
     */
    @ExceptionHandler(CustomException.class)
    public ResponseResult<Void> handleCustomException(CustomException e) {
        log.warn("业务异常: code={}, message={}", e.getStatus().value(), e.getMessage());

        return ResponseResult.fail(e.getStatus().value(), e.getMessage());
    }

    /**
     * 参数校验失败（覆盖 @RequestBody 校验的 MethodArgumentNotValidException 与表单绑定的 BindException）
     *
     * @param e
     *            绑定异常
     * @return 响应结果
     */
    @ExceptionHandler(BindException.class)
    public ResponseResult<Void> handleBindException(BindException e) {
        String message = e.getFieldErrors().stream()
            .map(error -> error.getDefaultMessage())
            .collect(java.util.stream.Collectors.joining("；"));

        log.warn("参数校验失败: {}", message);

        return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), message);
    }

    /**
     * 缺少必要的请求参数（如 @RequestParam 必填项未传）
     *
     * @param e
     *            缺参异常
     * @return 响应结果
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseResult<Void> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());

        return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), "缺少必要参数：" + e.getParameterName());
    }

    /**
     * 请求参数类型不匹配
     *
     * @param e
     *            类型不匹配异常
     * @return 响应结果
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseResult<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: {}", e.getName());

        return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), "参数类型不正确：" + e.getName());
    }

    /**
     * 上传文件超出大小限制（spring.servlet.multipart.max-file-size）
     *
     * @param e
     *            上传超限异常
     * @return 响应结果
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseResult<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超出大小限制: {}", e.getMessage());

        return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), "上传文件过大，请压缩后重试");
    }

    /**
     * 直接抛出的 HTTP 语义异常（如内部 OCR 接口），取其状态码与原因短语
     *
     * @param e
     *            ResponseStatusException
     * @return 响应结果
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseResult<Void> handleResponseStatusException(ResponseStatusException e) {
        String message = e.getReason() != null ? e.getReason() : "请求处理失败";

        log.warn("HTTP 语义异常: code={}, message={}", e.getStatusCode().value(), message);

        return ResponseResult.fail(e.getStatusCode().value(), message);
    }

    /**
     * 兜底：未预期的异常统一返回 500，避免向前端暴露堆栈信息
     *
     * @param e
     *            未预期异常
     * @return 响应结果
     */
    @ExceptionHandler(Exception.class)
    public ResponseResult<Void> handleException(Exception e) {
        log.error("系统未处理异常", e);

        return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "系统繁忙，请稍后重试");
    }
}
