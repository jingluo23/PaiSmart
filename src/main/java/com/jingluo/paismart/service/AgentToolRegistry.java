package com.jingluo.paismart.service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.jingluo.paismart.client.DeepSeekClient;
import com.jingluo.paismart.domain.response.ToolExecutionResult;
import com.jingluo.paismart.entity.SearchResult;
import com.jingluo.paismart.handler.ToolHandler;
import com.jingluo.paismart.model.FileUpload;
import com.jingluo.paismart.repository.FileUploadRepository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.DocStats;
import co.elastic.clients.elasticsearch._types.StoreStats;
import co.elastic.clients.elasticsearch.indices.IndicesStatsResponse;
import co.elastic.clients.elasticsearch.indices.stats.IndicesStats;

/**
 * Agent 工具注册表
 * <p>
 * 大模型可调用的工具集合，当前注册四个工具：
 * search_knowledge（知识库检索）、generate_summary（知识库摘要）、
 * submit_feedback（用户反馈）、knowledge_stats（知识库统计）。
 * 统一由 executeTool 入口分发，工具入参由大模型按工具描述生成。
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 17:03
 */
@Service
public class AgentToolRegistry {

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    /**
     * 未指定 topK/maxDocs 时的默认检索条数
     */
    private static final int DEFAULT_TOP_K = 5;

    /**
     * 单次工具检索条数上限，防止大模型生成过大的参数
     */
    private static final int MAX_SEARCH_DOCS = 20;

    /**
     * 知识库片段所在的 Elasticsearch 索引名
     */
    private static final String KNOWLEDGE_INDEX = "knowledge_base";

    /**
     * 工具名 -> 处理器的映射
     */
    private final Map<String, ToolHandler> handlers;

    public AgentToolRegistry(Map<String, ToolHandler> handlers) {
        this.handlers =
            Map.of("search_knowledge", this::executeSearchKnowledge, "generate_summary", this::executeGenerateSummary,
                "submit_feedback", this::executeSubmitFeedback, "knowledge_stats", this::executeKnowledgeStats);
    }

    /**
     * knowledge_stats 工具：统计知识库规模
     * <p>
     * 汇总 MySQL 文档数与 Elasticsearch 片段数、已删除片段数、存储大小、最近更新时间。
     *
     * @param arguments 工具入参（本工具无必填参数）
     * @param userId    发起调用的用户 ID
     * @param onChunk   流式回调（本工具不产生流式输出）
     * @return 工具执行结果
     */
    private ToolExecutionResult executeKnowledgeStats(Map<String, Object> arguments, String userId,
        Consumer<String> onChunk) {
        try {
            IndicesStatsResponse statsResponse = elasticsearchClient.indices().stats(s -> s.index(KNOWLEDGE_INDEX));
            IndicesStats indexStats = statsResponse.indices().get(KNOWLEDGE_INDEX);
            DocStats docStats = indexStats != null && indexStats.total() != null ? indexStats.total().docs() : null;
            StoreStats storeStats =
                indexStats != null && indexStats.total() != null ? indexStats.total().store() : null;

            long documentCount = fileUploadRepository.count();
            long fragmentCount = docStats != null ? docStats.count() : 0L;
            Long deletedFragmentCount = docStats != null ? docStats.deleted() : null;
            Long storeSizeInBytes = storeStats != null ? storeStats.sizeInBytes() : null;
            LocalDateTime latestUpdatedAt =
                fileUploadRepository.findFirstByOrderByMergedAtDesc().map(this::resolveLatestUpdatedAt).orElse(null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("index", KNOWLEDGE_INDEX);
            data.put("documentCount", documentCount);
            data.put("fragmentCount", fragmentCount);
            data.put("deletedFragmentCount", deletedFragmentCount);
            data.put("storeSizeInBytes", storeSizeInBytes);
            data.put("latestUpdatedAt", latestUpdatedAt);

            return new ToolExecutionResult("knowledge_stats", true, formatKnowledgeStats(data), data);
        } catch (Exception e) {
            throw new RuntimeException("获取知识库统计信息失败", e);
        }
    }

    /**
     * 将知识库统计数据格式化为面向大模型的文本
     *
     * @param data 统计数据
     * @return 格式化文本
     */
    private String formatKnowledgeStats(Map<String, Object> data) {
        return "知识库统计：" + "\n- MySQL 文档总数：" + data.get("documentCount") + "\n- Elasticsearch 片段总数："
            + data.get("fragmentCount") + "\n- ES 已删除片段数：" + nullToDash(data.get("deletedFragmentCount"))
            + "\n- ES 存储大小(bytes)：" + nullToDash(data.get("storeSizeInBytes")) + "\n- 最近更新时间："
            + nullToDash(data.get("latestUpdatedAt"));
    }

    private String nullToDash(Object value) {
        return Objects.isNull(value) ? "-" : String.valueOf(value);
    }

    /**
     * 取知识库最近更新时间：优先使用分片合并时间，无则回退到上传创建时间
     *
     * @param fileUpload 最近一次上传记录
     * @return 最近更新时间
     */
    private LocalDateTime resolveLatestUpdatedAt(FileUpload fileUpload) {
        if (Objects.nonNull(fileUpload.getMergedAt())) {
            return fileUpload.getMergedAt();
        }

        return fileUpload.getCreatedAt();
    }

    /**
     * submit_feedback 工具：记录用户反馈
     * <p>
     * 以 Redis Hash 存储：key=feedback:{userId}，field=时间戳，value=评价与原因。
     *
     * @param arguments 工具入参，rating 必填（good/bad），reason 可选
     * @param userId    发起调用的用户 ID
     * @param onChunk   流式回调（本工具不产生流式输出）
     * @return 工具执行结果
     */
    private ToolExecutionResult executeSubmitFeedback(Map<String, Object> arguments, String userId,
        Consumer<String> onChunk) {
        requireUserId(userId);
        String rating = getRequiredString(arguments, "rating").toLowerCase(Locale.ROOT);
        if (!"good".equals(rating) && !"bad".equals(rating)) {
            throw new IllegalArgumentException("rating 只允许 good 或 bad");
        }

        String reason = getOptionalString(arguments, "reason");
        String key = "feedback:" + userId;
        String field = String.valueOf(System.currentTimeMillis());
        String value = StringUtils.isBlank(reason) ? "rating=" + rating : "rating=" + rating + "; reason=" + reason;
        stringRedisTemplate.opsForHash().put(key, field, value);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("field", field);
        data.put("rating", rating);
        data.put("reason", reason);

        return new ToolExecutionResult("submit_feedback", true, "已记录用户反馈: " + value, data);
    }

    /**
     * generate_summary 工具：先检索再生成知识库摘要
     * <p>
     * 按 maxDocs 检索知识库片段后调用 DeepSeekClient 流式生成摘要；
     * 增量文本已通过 onChunk 推送，因此 streamedToUser 标记为 true 避免重复推送。
     *
     * @param arguments 工具入参，topic 必填，maxDocs 可选（默认 5，上限 20）
     * @param userId    发起调用的用户 ID，同时用于检索权限过滤与配额结算
     * @param onChunk   流式回调，摘要增量文本实时推送
     * @return 工具执行结果
     */
    private ToolExecutionResult executeGenerateSummary(Map<String, Object> arguments, String userId,
        Consumer<String> onChunk) {
        requireUserId(userId);
        String topic = getRequiredString(arguments, "topic");
        int maxDocs = getInt(arguments, "maxDocs", DEFAULT_TOP_K, 1, MAX_SEARCH_DOCS);

        List<SearchResult> results = hybridSearchService.searchWithPermission(topic, userId, maxDocs);
        String summary = deepSeekClient.summarize(userId, topic, results, onChunk);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", topic);
        data.put("maxDocs", maxDocs);
        data.put("sourceCount", results.size());
        data.put("sources", results);

        String content = "主题：" + topic + "\n" + "检索片段数：" + results.size() + "\n\n" + summary;

        return new ToolExecutionResult("generate_summary", true, content, data, Objects.nonNull(onChunk));
    }

    /**
     * search_knowledge 工具：带权限的知识库混合检索
     *
     * @param arguments 工具入参，query 必填，topK 可选（默认 5，上限 20）
     * @param userId    发起调用的用户 ID，用于数据权限过滤
     * @param onChunk   流式回调（本工具不产生流式输出）
     * @return 工具执行结果
     */
    private ToolExecutionResult executeSearchKnowledge(Map<String, Object> arguments, String userId,
        Consumer<String> onChunk) {
        requireUserId(userId);

        String query = getRequiredString(arguments, "query");

        int topK = getInt(arguments, "topK", DEFAULT_TOP_K, 1, MAX_SEARCH_DOCS);

        List<SearchResult> results = hybridSearchService.searchWithPermission(query, userId, topK);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("topK", topK);
        data.put("results", results);

        return new ToolExecutionResult("search_knowledge", true, formatSearchResults(results), data);
    }

    /**
     * 将检索结果格式化为面向大模型的文本
     * <p>
     * 输出带编号的片段列表（文件名、来源标识、相关度、片段内容），
     * 并附带回答约束提示：只能基于检索片段作答、不足时需说明。
     *
     * @param results 检索结果列表
     * @return 格式化文本
     */
    private String formatSearchResults(List<SearchResult> results) {
        if (CollectionUtils.isEmpty(results)) {
            return "未检索到相关知识库片段。";
        }

        StringBuilder output = new StringBuilder("检索到 ").append(results.size()).append(" 个知识库片段。")
            .append("请基于这些片段回答用户问题；不得声称知识库暂无相关信息。").append("如果片段信息不足，请说明“基于已检索片段只能确认……”并标注来源编号。");
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            output.append("\n\n[").append(i + 1).append("] ");

            if (StringUtils.isNotBlank(result.getFileName())) {
                output.append(result.getFileName()).append(" ");
            }

            output.append("(fileMd5=").append(result.getFileMd5()).append(", chunkId=").append(result.getChunkId());
            if (Objects.nonNull(result.getPageNumber())) {
                output.append(", page=").append(result.getPageNumber());
            }

            if (Objects.nonNull(result.getScore())) {
                output.append(", score=").append(String.format(Locale.ROOT, "%.4f", result.getScore()));
            }

            output.append(")\n").append(limitText(
                result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent(), 1200));
        }

        return output.toString();
    }

    /**
     * 截断文本到指定长度，超出部分以 "..." 结尾，防止单片段占用过多上下文
     *
     * @param text     原文本
     * @param maxChars 最大字符数
     * @return 截断后的文本
     */
    private String limitText(String text, int maxChars) {
        if (StringUtils.isBlank(text)) {
            return "";
        }

        if (text.length() <= maxChars) {
            return text;
        }

        return text.substring(0, maxChars) + "...";
    }

    /**
     * 读取整型工具参数，非法或缺省时取默认值，并钳制到 [min, max] 区间
     *
     * @param arguments     工具入参
     * @param name          参数名
     * @param defaultValue  默认值
     * @param min           最小值
     * @param max           最大值
     * @return 处理后的参数值
     */
    private int getInt(Map<String, Object> arguments, String name, int defaultValue, int min, int max) {
        Object raw = arguments.get(name);
        if (Objects.isNull(raw) || String.valueOf(raw).isBlank()) {
            return defaultValue;
        }

        int value;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else {
            value = Integer.parseInt(String.valueOf(raw));
        }

        return Math.max(min, Math.min(max, value));
    }

    /**
     * 读取必填字符串工具参数，缺失或为空时抛出异常
     *
     * @param arguments 工具入参
     * @param name      参数名
     * @return 去除首尾空白后的参数值
     */
    private String getRequiredString(Map<String, Object> arguments, String name) {
        String value = getOptionalString(arguments, name);
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(name + " 不能为空");
        }

        return value.trim();
    }

    /**
     * 读取可选字符串工具参数
     *
     * @param arguments 工具入参
     * @param name      参数名
     * @return 去除首尾空白后的参数值，参数不存在时返回 null
     */
    private String getOptionalString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (Objects.isNull(value)) {
            return null;
        }

        return String.valueOf(value).trim();
    }

    /**
     * 校验用户 ID 非空：带权限的工具（检索/反馈）必须知道操作者
     *
     * @param userId 用户 ID
     */
    private void requireUserId(String userId) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("工具调用缺少 userId，无法执行带权限的知识库或反馈操作");
        }
    }

    /**
     * 执行指定工具（无流式回调场景）
     *
     * @param name      工具名称
     * @param arguments 工具入参
     * @param userId    发起调用的用户 ID
     * @return 工具执行结果
     */
    public ToolExecutionResult executeTool(String name, Map<String, Object> arguments, String userId) {
        return executeTool(name, arguments, userId, null);
    }

    /**
     * 执行指定工具的统一入口
     *
     * @param name      工具名称
     * @param arguments 工具入参，可为 null（按空 Map 处理）
     * @param userId    发起调用的用户 ID
     * @param onChunk   流式回调，可为 null
     * @return 工具执行结果
     * @throws IllegalArgumentException 工具未注册时抛出
     */
    public ToolExecutionResult executeTool(String name, Map<String, Object> arguments, String userId,
        Consumer<String> onChunk) {
        ToolHandler handler = handlers.get(name);
        if (Objects.isNull(handler)) {
            throw new IllegalArgumentException("未注册的工具: " + name);
        }

        return handler.execute(CollectionUtils.isEmpty(arguments) ? Collections.emptyMap() : arguments, userId,
            onChunk);
    }
}
