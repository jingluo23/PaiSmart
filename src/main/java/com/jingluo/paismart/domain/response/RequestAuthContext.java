package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 10:20
 * @Desc: 请求鉴权上下文，封装从请求（Header 或 URL 参数）中解析出的用户标识与组织标签
 */
@AllArgsConstructor
@Data
public class RequestAuthContext {

    /**
     * 用户 ID，取自 JWT；未携带有效 token 时为 null，表示匿名访问
     */
    private String userId;

    /**
     * 用户组织标签（逗号分隔），用于文件访问范围过滤
     */
    private String orgTags;
}
