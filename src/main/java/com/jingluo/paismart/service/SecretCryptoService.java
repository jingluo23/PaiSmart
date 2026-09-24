package com.jingluo.paismart.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.micrometer.common.util.StringUtils;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 16:58
 * @Desc: 密钥加密与脱敏服务
 */
@Service
public class SecretCryptoService {

    @Value("${model-provider.security.secret-key:${jwt.secret-key:}}")
    private String base64Secret;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int TAG_LENGTH_BITS = 128;

    private static final int IV_LENGTH_BYTES = 12;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 密钥脱敏显示：长度大于 8 位时保留前 4 位与后 4 位，中间以 **** 代替
     *
     * @param raw
     *            原始密钥
     * @return 脱敏后的密钥；长度不足 8 位时返回 ****
     */
    public String mask(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }

        if (raw.length() <= 8) {
            return "****";
        }

        return raw.substring(0, 4) + "****" + raw.substring(raw.length() - 4);
    }

    /**
     * 密钥解密
     *
     * @param ciphertext
     * @return
     */
    public String decrypt(String ciphertext) {
        if (StringUtils.isBlank(ciphertext)) {
            return null;
        }

        try {
            String[] parts = ciphertext.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("密文格式不正确");
            }

            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);

            SecretKeySpec keySpec = new SecretKeySpec(Base64.getDecoder().decode(base64Secret), "AES");

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] decrypted = cipher.doFinal(encrypted);

            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("模型配置密钥解密失败", exception);
        }
    }

    /**
     * 密钥加密
     *
     * @param raw
     * @return
     */
    public String encrypt(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }

        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);

            SecretKeySpec keySpec = new SecretKeySpec(Base64.getDecoder().decode(base64Secret), "AES");

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(raw.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception exception) {
            throw new IllegalStateException("模型配置密钥加密失败", exception);
        }
    }
}
