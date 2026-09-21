package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 16:51
 * @Desc: 资源权限信息载体，供组织标签授权过滤器判断用户对资源的访问权限
 */
@AllArgsConstructor
@Data
public class ResourceInfo {

    /**
     * 资源拥有者用户标识
     */
    private String owner;

    /**
     * 资源所属组织标签
     */
    private String orgTag;

    /**
     * 是否公开资源
     */
    private boolean isPublic;
}
