package com.jingluo.paismart.controller;

import java.io.IOException;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.jingluo.paismart.domain.response.ResponseResult;
import com.jingluo.paismart.service.AliyunOcrService;
import com.jingluo.paismart.service.LiteParseOcrAdapterService;

/**
 * @Author: 鲸落
 * @Date: 2026/9/20 9:49
 * @Desc: 内部 OCR 接口：为 LiteParse 客户端提供基于阿里云 OCR 的识别端点，并提供阿里云 OCR 健康检查
 */
@RestController
@RequestMapping("/api/v1/internal/ocr")
public class InternalOcrController {

    @Autowired
    private LiteParseOcrAdapterService liteParseOcrAdapterService;

    @Autowired
    private AliyunOcrService aliyunOcrService;

    /**
     * LiteParse 格式的图像识别接口：接收上传图像，调用阿里云 OCR 后返回 LiteParse 兼容的识别结果
     * <p>
     * 兼容多个请求路径，避免 LiteParse 客户端因版本差异使用不同端点路径导致 404
     * </p>
     *
     * @param file
     *            待识别的图像文件
     * @param token
     *            调用凭证，配置了回调 token 时必传，校验失败返回 401
     */
    @PostMapping(path = {"/liteparse", "/liteparse/", "/liteparse/ocr"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseResult<Map<String, Object>> recognizeForLiteParse(@RequestParam("file") MultipartFile file,
        @RequestParam(value = "token", required = false) String token) throws IOException {
        if (!StringUtils.hasText(file.getOriginalFilename()) && file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty OCR file");
        }

        return ResponseResult.success(liteParseOcrAdapterService.recognize(file, token));
    }

    /**
     * 阿里云 OCR 健康检查：返回功能开关、访问密钥配置状态及是否要求 token，用于部署后的连通性排查
     */
    @PostMapping(path = "/aliyun/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseResult<Map<String, Object>>
        aliyunOcrHealth(@RequestParam(value = "token", required = false) String token) {
        aliyunOcrService.verifyCallbackToken(token);

        return ResponseResult.success(Map.of("enabled", aliyunOcrService.isEnabled(), "configured",
            aliyunOcrService.isConfigured(), "tokenRequired", aliyunOcrService.isCallbackTokenRequired()));
    }
}
