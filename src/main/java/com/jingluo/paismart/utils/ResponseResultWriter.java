package com.jingluo.paismart.utils;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingluo.paismart.domain.response.ResponseResult;

import jakarta.servlet.http.HttpServletResponse;

/**
 * @date 2026/9/23
 * @Description ResponseResult JSON 响应写出工具，供 Security 过滤器层等
 *              无法经过 GlobalExceptionHandler 的场景统一返回 ResponseResult 结构；
 *              HTTP 状态保持 200，业务码由 body 携带，由前端按 code 统一判断成败
 */
@Component
public class ResponseResultWriter {

    private final ObjectMapper objectMapper;

    public ResponseResultWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将失败响应以 JSON 形式写出
     *
     * @param response
     *            HTTP 响应
     * @param code
     *            业务状态码
     * @param message
     *            提示信息
     * @throws IOException
     *             响应写出失败
     */
    public void write(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ResponseResult.fail(code, message)));
    }
}
