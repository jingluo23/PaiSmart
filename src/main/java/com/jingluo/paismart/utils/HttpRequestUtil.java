package com.jingluo.paismart.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * HTTP 请求工具类，提供客户端 IP 解析与请求体读取能力
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 10:43
 */
@Slf4j
public class HttpRequestUtil {

    /**
     * 从当前请求上下文中获取客户端真实 IP
     */
    public static String getClientIp() {
        // 从上下文中获取 HttpServletRequest
        HttpServletRequest request =
            ((ServletRequestAttributes)RequestContextHolder.currentRequestAttributes()).getRequest();
        if (Objects.nonNull(request)) {
            return resolveClientIp(request);
        }

        return "";
    }

    /**
     * 解析客户端真实 IP，按代理头优先级依次取第一个有效值：CF-Connecting-IP、True-Client-IP、 X-Forwarded-For 首个有效
     * IP、X-Real-IP、Proxy-Client-IP、WL-Proxy-Client-IP，最后兜底 remoteAddr
     */
    public static String resolveClientIp(HttpServletRequest request) {
        return firstUsableIp(request.getHeader("CF-Connecting-IP"), request.getHeader("True-Client-IP"),
            extractForwardedForIp(request.getHeader("X-Forwarded-For")), request.getHeader("X-Real-IP"),
            request.getHeader("Proxy-Client-IP"), request.getHeader("WL-Proxy-Client-IP"), request.getRemoteAddr())
            .orElse("unknown");
    }

    /**
     * 从 X-Forwarded-For 头中提取第一个有效 IP（该头可能包含多个逗号分隔的 IP）
     */
    private static String extractForwardedForIp(String xForwardedFor) {
        if (StringUtils.isBlank(xForwardedFor)) {
            return null;
        }

        return Arrays.stream(xForwardedFor.split(",")).map(String::trim).filter(HttpRequestUtil::isUsableIp).findFirst()
            .orElse(null);
    }

    /**
     * 返回候选 IP 列表中第一个有效（非空白且不为 unknown）的值
     */
    private static Optional<String> firstUsableIp(String... candidates) {
        return Arrays.stream(candidates).filter(HttpRequestUtil::isUsableIp).findFirst();
    }

    /**
     * 判断 IP 值是否有效（非空白且不为 unknown）
     */
    private static boolean isUsableIp(String ip) {
        return StringUtils.isNotBlank(ip) && !"unknown".equalsIgnoreCase(ip);
    }

    /**
     * 以 UTF-8 读取请求体内容并拼接为字符串（用于微信回调报文验签）
     */
    public static String readReqData(HttpServletRequest request) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while (StringUtils.isNotBlank(line = reader.readLine())) {
                stringBuilder.append(line);
            }

            return stringBuilder.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (Objects.nonNull(reader)) {
                try {
                    reader.close();
                } catch (IOException e) {
                    log.warn("请求参数解析异常! {}", request.getRequestURI(), e);
                }
            }
        }
    }
}
