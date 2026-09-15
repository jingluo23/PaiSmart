package com.jingluo.paismart.domain.request;

import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 16:22
 * @Desc: 创建组织标签请求
 */
@Data
public class OrgTagRequest {

    /**
     * 标签唯一标识，为空时系统根据名称自动生成
     */
    private String tagId;

    /**
     * 标签名称
     */
    private String name;

    /**
     * 标签描述
     */
    private String description;

    /**
     * 父标签 ID，为空表示顶级标签
     */
    private String parentTag;

    /**
     * 上传文件大小上限（MB），为空表示不限制
     */
    private Long uploadMaxSizeMb;
}
