package com.jingluo.paismart.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 15:28
 * @Desc: 密码工具类，基于 BCrypt 提供密码加密与匹配校验
 */
public class PasswordUtil {

    /**
     * BCrypt 编码器（线程安全，可复用）
     */
    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 加密密码
     *
     * @param rawPassword
     *            明文密码
     * @return 加密后的密码
     */
    public static String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 验证密码是否匹配
     *
     * @param rawPassword
     *            明文密码
     * @param encodedPassword
     *            加密后的密码
     * @return 是否匹配
     */
    public static boolean matches(String rawPassword, String encodedPassword) {
        return encoder.matches(rawPassword, encodedPassword);
    }
}
