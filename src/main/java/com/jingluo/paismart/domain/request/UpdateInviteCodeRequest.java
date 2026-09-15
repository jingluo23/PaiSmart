package com.jingluo.paismart.domain.request;

import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 16:11
 * @Desc: 更新邀请码请求参数
 */
@Data
public class UpdateInviteCodeRequest {

    /**
     * 新的邀请码字符串
     */
    private String code;

    /**
     * 新的最大可用次数
     */
    private Integer maxUses;
}
