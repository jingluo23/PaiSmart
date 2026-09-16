package com.jingluo.paismart.config;

import java.io.StringReader;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.apache.http.ConnectionClosedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 11:10
 * @Desc: Elasticsearch 索引初始化器，应用启动时读取 classpath 下的 mapping JSON，自动创建 knowledge_base 索引
 */
@Slf4j
@Order(2)
@Component
@ConditionalOnProperty(name = "elasticsearch.init.enabled", havingValue = "true", matchIfMissing = true)
public class EsIndexInitializer implements CommandLineRunner {

    /** ES 高级客户端，由 EsConfig 提供 */
    @Autowired
    private ElasticsearchClient esClient;

    /** 索引 mapping 定义文件，位于 classpath:es-mappings/knowledge_base.json */
    @Value("classpath:es-mappings/knowledge_base.json")
    private org.springframework.core.io.Resource mappingResource;

    /** 以下连接配置仅用于初始化失败时在诊断信息中拼接 ES 地址 */
    @Value("${elasticsearch.host}")
    private String host;

    @Value("${elasticsearch.port}")
    private int port;

    @Value("${elasticsearch.scheme:https}")
    private String scheme;

    @Value("${elasticsearch.username:elastic}")
    private String username;

    /**
     * 应用启动后执行索引初始化；连接被关闭类异常会等待 5 秒后重试一次，重试仍失败或遇到其他异常时， 抛出携带根因类型与排查建议的 RuntimeException，阻断应用启动
     *
     * @param args
     *            应用启动参数
     * @throws Exception
     *             初始化失败（含重试后仍失败）时抛出
     */
    @Override
    public void run(String... args) throws Exception {
        try {
            initializeIndex();
        } catch (Exception exception) {
            // 特别处理连接关闭异常，尝试重新连接
            if (exception instanceof ConnectionClosedException || (Objects.nonNull(exception.getCause())
                && exception.getCause() instanceof ConnectionClosedException)) {
                log.warn("Elasticsearch连接已关闭，等待5秒后重试...");

                try {
                    // 等待5秒后重试
                    Thread.sleep(5000);
                    // 重新尝试初始化索引
                    initializeIndex();
                } catch (Exception retryException) {
                    String diagnostic = buildDiagnosticMessage(retryException);
                    log.warn("重试初始化索引失败。{}", diagnostic, retryException);

                    throw new RuntimeException("初始化索引失败，重试也未能成功。" + diagnostic, retryException);
                }
            } else {
                String diagnostic = buildDiagnosticMessage(exception);
                log.warn("初始化索引失败。{}", diagnostic, exception);

                throw new RuntimeException("初始化索引失败。" + diagnostic, exception);
            }
        }
    }

    /**
     * 初始化索引的核心逻辑
     * 
     * @throws Exception
     */
    private void initializeIndex() throws Exception {
        // 检查索引是否存在
        BooleanResponse existsResponse = esClient.indices().exists(ExistsRequest.of(e -> e.index("knowledge_base")));
        if (!existsResponse.value()) {
            createIndex();
        } else {
            log.warn("索引 'knowledge_base' 已存在");
        }
    }

    /**
     * 创建索引
     * 
     * @throws Exception
     */
    private void createIndex() throws Exception {
        // 读取 JSON 文件内容，使用 InputStream 方式支持 JAR 包内资源
        String mappingJson;
        try (var inputStream = mappingResource.getInputStream()) {
            mappingJson = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 创建索引并应用映射
        CreateIndexRequest createIndexRequest = CreateIndexRequest.of(c -> c.index("knowledge_base") // 索引名称
            .withJson(new StringReader(mappingJson)) // 使用 JSON 文件定义映射
        );

        esClient.indices().create(createIndexRequest);
    }

    /**
     * 根据异常根因生成排查提示信息，覆盖连接失败、HTTP/HTTPS 协议不匹配、认证失败、缺少 IK 分词器、 向量维度不匹配等常见场景，其余情况给出通用排查建议
     *
     * @param exception
     *            初始化过程中抛出的异常
     * @return 包含根因类型、根因信息与排查建议的诊断文案
     */
    private String buildDiagnosticMessage(Exception exception) {
        Throwable rootCause = getRootCause(exception);
        String rootMessage = safeMessage(rootCause);
        String normalizedMessage = rootMessage.toLowerCase(Locale.ROOT);
        String endpoint = scheme + "://" + host + ":" + port;
        List<String> hints = new ArrayList<>();

        hints.add("ES地址=" + endpoint);

        if (isConnectionProblem(rootCause, normalizedMessage)) {
            hints.add("当前看起来是连接失败，请先确认 Elasticsearch 已启动，并且 " + endpoint + " 能访问");
        }

        if (isSslMismatch(normalizedMessage)) {
            hints.add("当前更像是 HTTP/HTTPS 协议不匹配，请核对 ELASTICSEARCH_SCHEME 与实际 ES 配置");
        }

        if (isAuthenticationProblem(normalizedMessage)) {
            hints.add("当前更像是账号或密码不正确，请核对 ELASTICSEARCH_USERNAME / ELASTICSEARCH_PASSWORD");
        }

        if (normalizedMessage.contains("ik_max_word") || normalizedMessage.contains("ik_smart")) {
            hints.add("当前索引 mapping 依赖 IK 分词器，请确认 ES 已安装 analysis-ik 插件");
        }

        if (normalizedMessage.contains("dense_vector") && normalizedMessage.contains("dims")) {
            hints.add("当前更像是向量字段维度不匹配，请确认 embedding.dimension 与索引 mapping 中的 dims 一致");
        }

        if (hints.size() == 1) {
            hints.add("请查看根因异常后再排查 ES 协议、端口、认证和 mapping 配置");
        }

        return " 根因类型=" + rootCause.getClass().getSimpleName() + "，根因信息=" + rootMessage + "。排查建议："
            + String.join("；", hints);
    }

    /**
     * 沿异常链向下追溯，返回最底层的根因异常
     *
     * @param throwable
     *            任意层级的异常
     * @return 异常链最底层的根因
     */
    private Throwable getRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (Objects.nonNull(current.getCause()) && current.getCause() != current) {
            current = current.getCause();
        }

        return current;
    }

    /**
     * 判断根因是否属于网络连接类问题（拒绝连接、连接超时、域名解析失败、连接被重置等）
     *
     * @param rootCause
     *            根因异常
     * @param normalizedMessage
     *            转为小写后的根因异常消息
     * @return 属于连接类问题返回 true
     */
    private boolean isConnectionProblem(Throwable rootCause, String normalizedMessage) {
        return rootCause instanceof ConnectException || normalizedMessage.contains("connection refused")
            || normalizedMessage.contains("connect timed out") || normalizedMessage.contains("connection timed out")
            || normalizedMessage.contains("failed to connect") || normalizedMessage.contains("no such host")
            || normalizedMessage.contains("unknownhost") || normalizedMessage.contains("no reachable node")
            || normalizedMessage.contains("connection reset");
    }

    /**
     * 判断根因是否属于 TLS/SSL 类问题（证书校验失败、握手失败、以明文请求 HTTPS 端口等）
     *
     * @param normalizedMessage
     *            转为小写后的根因异常消息
     * @return 属于 SSL 类问题返回 true
     */
    private boolean isSslMismatch(String normalizedMessage) {
        return normalizedMessage.contains("pkix") || normalizedMessage.contains("ssl")
            || normalizedMessage.contains("tls") || normalizedMessage.contains("handshake")
            || normalizedMessage.contains("plaintext connection")
            || normalizedMessage.contains("unrecognized ssl message");
    }

    /**
     * 判断根因是否属于认证授权类问题（401/403 响应、security_exception、认证失败等）
     *
     * @param normalizedMessage
     *            转为小写后的根因异常消息
     * @return 属于认证授权类问题返回 true
     */
    private boolean isAuthenticationProblem(String normalizedMessage) {
        return normalizedMessage.contains("security_exception") || normalizedMessage.contains("authentication")
            || normalizedMessage.contains("unauthorized") || normalizedMessage.contains("status line [http/1.1 401")
            || normalizedMessage.contains("status line [http/1.1 403");
    }

    /**
     * 安全获取异常消息，消息为空白时返回占位文案，避免诊断信息中出现 null
     *
     * @param throwable
     *            目标异常
     * @return 异常消息或占位文案
     */
    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return StringUtils.isBlank(message) ? "<无异常消息>" : message;
    }
}
