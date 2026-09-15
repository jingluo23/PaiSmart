package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 模型提供者连通性测试结果视图
 *
 * @Author: 鲸落
 * @Date: 2026/9/15 14:44
 * @Desc: 返回连通性测试是否成功、结果描述及请求耗时
 */
@AllArgsConstructor
@Data
public class ConnectivityTestView {

    /**
     * 是否连接成功
     */
    private boolean success;

    /**
     * 测试结果描述，成功时为提示语，失败时为失败原因
     */
    private String message;

    /**
     * 请求耗时（毫秒）
     */
    private long latencyMs;
}
