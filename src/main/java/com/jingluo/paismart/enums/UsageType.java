package com.jingluo.paismart.enums;

import lombok.Getter;

/**
 * @Author: 鲸落
 * @Date: 2026/9/18 17:27
 * @Desc: Embedding 使用场景类型，区分上传与查询两类调用，分别适用不同的配额与限流策略
 */
@Getter
public enum UsageType {

    /**
     * 文档上传时的向量化调用
     */
    UPLOAD,

    /**
     * 检索查询时的向量化调用
     */
    QUERY;
}
