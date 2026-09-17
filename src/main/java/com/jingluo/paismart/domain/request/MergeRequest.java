package com.jingluo.paismart.domain.request;

import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 15:00
 * @Desc: 分片合并请求参数
 */
@Data
public class MergeRequest {

    /**
     * 文件整体 MD5，标识待合并的文件
     */
    private String fileMd5;

    /**
     * 合并后的文件名
     */
    private String fileName;
}
