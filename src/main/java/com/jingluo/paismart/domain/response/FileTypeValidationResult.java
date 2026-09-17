package com.jingluo.paismart.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 13:56
 * @Desc: 文件类型校验结果
 */
@AllArgsConstructor
@Data
public class FileTypeValidationResult {

    /**
     * 是否通过校验
     */
    private boolean valid;

    /**
     * 校验结果提示信息
     */
    private String message;

    /**
     * 文件类型描述
     */
    private String fileType;

    /**
     * 文件扩展名（小写）
     */
    private String extension;
}
