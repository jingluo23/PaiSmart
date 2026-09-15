package com.jingluo.paismart.domain.request;

import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 17:00
 * @Desc: 更新组织标签请求
 */
@Data
public class OrgTagUpdateRequest {

    /**
     * 标签名称
     */
    private String name;

    /**
     * 标签描述
     */
    private String description;

    /**
     * 父标签 ID，为空表示移动为顶级标签
     */
    private String parentTag;

    /**
     * 上传文件大小上限（MB），为空表示不限制
     */
    private Long uploadMaxSizeMb;
}
