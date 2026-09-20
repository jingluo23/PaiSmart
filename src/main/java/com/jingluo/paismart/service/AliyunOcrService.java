package com.jingluo.paismart.service;

import java.io.ByteArrayInputStream;
import java.util.Objects;

import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextRequest;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextResponse;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.Common;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/20 9:51
 * @Desc: 阿里云 OCR 服务：封装阿里云文字识别 SDK 的调用、鉴权与配置管理，返回原始识别结果 JSON
 */
@Slf4j
@Service
public class AliyunOcrService {

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 接口调用凭证，配置后所有请求必须携带相同 token 才能通过校验，留空表示不启用鉴权
     */
    @Value("${aliyun.ocr.callback-token:}")
    private String callbackToken;

    /**
     * 阿里云 OCR 功能总开关，关闭时识别接口返回 503
     */
    @Value("${aliyun.ocr.enabled:false}")
    private boolean enabled;

    /**
     * 阿里云访问密钥 ID，支持直接配置或回退到环境变量（如 ECS 挂载的临时凭证）
     */
    @Value("${aliyun.ocr.access-key-id:${ALIBABA_CLOUD_ACCESS_KEY_ID:}}")
    private String accessKeyId;

    /**
     * 阿里云访问密钥 Secret，支持直接配置或回退到环境变量
     */
    @Value("${aliyun.ocr.access-key-secret:${ALIBABA_CLOUD_ACCESS_KEY_SECRET:}}")
    private String accessKeySecret;

    /**
     * OCR 模型类型：Advanced 为高精版（支持高级输出配置），其他取值参考阿里云文档
     */
    @Value("${aliyun.ocr.type:Advanced}")
    private String type;

    /**
     * 是否返回原始坐标，开启后保留识别元素的原始像素坐标
     */
    @Value("${aliyun.ocr.output-oricoord:true}")
    private boolean outputOricoord;

    /**
     * 坐标输出格式：points 表示多边形顶点，line 表示线段端点
     */
    @Value("${aliyun.ocr.output-coordinate:points}")
    private String outputCoordinate;

    /**
     * 高精版是否按行输出识别结果
     */
    @Value("${aliyun.ocr.output-row:false}")
    private boolean outputRow;

    /**
     * 高精版是否按段落输出识别结果
     */
    @Value("${aliyun.ocr.output-paragraph:false}")
    private boolean outputParagraph;

    /**
     * 高精版是否输出表格识别结果
     */
    @Value("${aliyun.ocr.output-table:false}")
    private boolean outputTable;

    /**
     * SDK 建立连接的超时时间（毫秒）
     */
    @Value("${aliyun.ocr.connect-timeout-millis:10000}")
    private int connectTimeoutMillis;

    /**
     * SDK 等待响应的超时时间（毫秒），OCR 识别耗时较长需预留充足时间
     */
    @Value("${aliyun.ocr.read-timeout-millis:60000}")
    private int readTimeoutMillis;

    /**
     * 阿里云 OCR 服务接入点地址
     */
    @Value("${aliyun.ocr.endpoint:ocr-api.cn-hangzhou.aliyuncs.com}")
    private String endpoint;

    /**
     * SDK 客户端缓存，创建开销较大，采用双重检查锁延迟初始化避免重复构建
     */
    private volatile Client client;

    /**
     * 校验调用 token：仅当配置了 callback-token 时启用校验，不匹配则抛出 401
     */
    public void verifyCallbackToken(String token) {
        if (StringUtils.hasText(callbackToken) && !Objects.equals(callbackToken, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid OCR callback token");
        }
    }

    /**
     * 调用阿里云 OCR 识别图像，返回识别结果的 JSON 树
     * <p>
     * 前置校验功能开关与密钥配置；高精版模型额外下发行/段落/表格等高级输出配置； 识别失败统一包装为 502，便于上游区分识别服务异常
     * </p>
     *
     * @param imageBytes
     *            图像二进制内容
     * @return 阿里云返回的识别结果 JSON
     */
    public JsonNode recognize(byte[] imageBytes) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "阿里云 OCR 已禁用");
        }
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "阿里云 OCR 访问密钥未配置");
        }

        if (ArrayUtils.isEmpty(imageBytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "空 OCR 图像");
        }

        try {
            RecognizeAllTextRequest request =
                new RecognizeAllTextRequest().setBody(new ByteArrayInputStream(imageBytes))
                    .setType(defaultIfBlank(type, "Advanced")).setOutputOricoord(outputOricoord);

            if (StringUtils.hasText(outputCoordinate)) {
                request.setOutputCoordinate(outputCoordinate.trim());
            }
            if ("Advanced".equalsIgnoreCase(defaultIfBlank(type, "Advanced"))) {
                request.setAdvancedConfig(new RecognizeAllTextRequest.RecognizeAllTextRequestAdvancedConfig()
                    .setOutputRow(outputRow).setOutputParagraph(outputParagraph).setOutputTable(outputTable));
            }

            RuntimeOptions runtimeOptions =
                new RuntimeOptions().setConnectTimeout(connectTimeoutMillis).setReadTimeout(readTimeoutMillis);

            RecognizeAllTextResponse response = getClient().recognizeAllTextWithOptions(request, runtimeOptions);
            if (Objects.isNull(response) || Objects.isNull(response.getBody())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "阿里云 OCR 返回了空响应");
            }

            String code = response.getBody().getCode();
            if (StringUtils.hasText(code) && !"200".equals(code)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "阿里云 OCR 失败: " + code + " " + response.getBody().getMessage());
            }

            return objectMapper.readTree(Common.toJSONString(response.getBody().getData()));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "阿里云 OCR 请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 SDK 客户端单例：首次调用时根据密钥与接入点构建，后续复用
     */
    private Client getClient() throws Exception {
        Client current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                Config config = new Config().setAccessKeyId(accessKeyId).setAccessKeySecret(accessKeySecret);
                config.endpoint = defaultIfBlank(endpoint, "ocr-api.cn-hangzhou.aliyuncs.com");
                client = new Client(config);
            }
            return client;
        }
    }

    /**
     * 获取非空值，如果为空则返回默认值
     */
    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    /**
     * 是否已配置密钥
     */
    public boolean isConfigured() {
        return StringUtils.hasText(accessKeyId) && StringUtils.hasText(accessKeySecret);
    }

    /**
     * 是否已启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 是否需要验证回调 token
     */
    public boolean isCallbackTokenRequired() {
        return StringUtils.hasText(callbackToken);
    }
}
