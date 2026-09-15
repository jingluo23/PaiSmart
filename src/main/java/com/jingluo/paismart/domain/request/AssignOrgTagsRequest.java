package com.jingluo.paismart.domain.request;

import java.util.List;

import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 16:45
 * @Desc: 组织标签分配请求，用于为指定用户批量分配组织标签
 */
@Data
public class AssignOrgTagsRequest {

    /** 待分配给用户的组织标签 ID 列表 */
    private List<String> orgTags;
}
