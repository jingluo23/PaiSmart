package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 15:37
 * @Desc: 聊天消息窗口限制
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class WindowLimitView {

    /**
     * 最大请求数
     */
    private int max;

    /**
     * 窗口大小（秒）
     */
    private long windowSeconds;
}
