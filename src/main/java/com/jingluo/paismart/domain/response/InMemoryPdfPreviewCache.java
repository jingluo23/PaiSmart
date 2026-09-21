package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 9:10
 * @Desc: PDF 单页预览的本地内存缓存条目，避免高频预览请求反复回源 Redis
 */
@AllArgsConstructor
@Data
public class InMemoryPdfPreviewCache {

    /**
     * 预览内容（单页 PDF 字节流）
     */
    private byte[] content;

    /**
     * 缓存过期时间戳（毫秒），超过该时间的条目视为失效
     */
    private long expiresAtMillis;
}
