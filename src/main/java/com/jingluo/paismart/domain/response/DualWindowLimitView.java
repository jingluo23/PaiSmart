package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 15:41
 * @Desc: 双窗口限流配置
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DualWindowLimitView {

    /**
     * 每分钟最大使用量
     */
    long minuteMax;

    /**
     * 每分钟窗口大小（秒）
     */
    long minuteWindowSeconds;

    /**
     * 每日最大使用量
     */
    long dayMax;

    /**
     * 每日窗口大小（秒）
     */
    long dayWindowSeconds;
}
