package com.jingluo.paismart.domain;

import java.io.Serializable;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * @author 鲸落
 * @date 2026/9/13 16:23
 * @Description 响应结果
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Builder
@AllArgsConstructor
public class ResponseResult<T> implements Serializable {

    /**
     * 状态码
     */
    private int code;

    /**
     * 提示信息
     */
    private String msg;

    /**
     * 数据
     */
    private T data;

    /**
     * 失败
     *
     * @param value 状态码
     * @param s     提示信息
     * @return 响应结果
     */
    public static <T> ResponseResult<T> fail(int value, String s) {
        return new ResponseResult<>(value, s, null);
    }

    /**
     * 成功
     *
     * @param data 数据
     * @return 响应结果
     */
    public static <T> ResponseResult<T> success(T data) {
        return new ResponseResult<>(HttpStatus.OK.value(), null, data);
    }

    /**
     * 成功
     *
     * @param msg
     * @return
     * @param <T>
     */
    public static <T> ResponseResult<T> success(String msg) {
        return new ResponseResult<>(HttpStatus.OK.value(), msg, null);
    }
}
