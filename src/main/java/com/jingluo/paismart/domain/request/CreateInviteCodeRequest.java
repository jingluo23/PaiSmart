package com.jingluo.paismart.domain.request;

import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 15:34
 * @Desc: 创建邀请码请求参数
 */
@Data
public class CreateInviteCodeRequest {

    /**
     * 自定义邀请码（可选，仅在批量数量为 1 时生效）
     */
    private String code;

    /**
     * 每个邀请码的最大可用次数（可选，默认 1）
     */
    private Integer maxUses;

    /**
     * 批量创建数量（可选，默认 1，最大 100）
     */
    private Integer count;
}
