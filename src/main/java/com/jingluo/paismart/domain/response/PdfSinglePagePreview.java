package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 14:13
 * @Desc: PDF 单页预览结果，携带内容字节流与缓存命中标记
 */
@AllArgsConstructor
@Data
public class PdfSinglePagePreview {

    /**
     * 单页 PDF 内容字节流
     */
    private byte[] content;

    /**
     * 是否命中缓存（本地内存或 Redis），false 表示本次实时生成
     */
    private boolean cacheHit;
}
