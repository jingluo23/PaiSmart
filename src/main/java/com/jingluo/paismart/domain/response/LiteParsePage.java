package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 15:41
 * @Desc: LiteParse 解析输出的单页文本
 */
@AllArgsConstructor
@Data
public class LiteParsePage {

    /**
     * 页码（从 1 开始）
     */
    private int pageNumber;

    /**
     * 该页解析出的文本内容
     */
    private String text;
}
