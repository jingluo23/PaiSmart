package com.jingluo.paismart.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/20 9:50
 * @Desc: LiteParse OCR 适配服务：将阿里云 OCR 的原始识别结果转换为 LiteParse 客户端兼容的 {text, bbox, confidence} 结构，使现有 LiteParse
 *        对接流程无需改动即可切换识别引擎
 */
@Slf4j
@Service
public class LiteParseOcrAdapterService {

    @Autowired
    private AliyunOcrService aliyunOcrService;

    /**
     * 识别入口：校验 token 后调用阿里云 OCR，将返回的块级明细转换为 LiteParse 结果集
     * <p>
     * 处理顺序：提取 SubImages 下的块级明细 → 无块级结果时按行拆分全文兜底 → 按 bbox 先纵后横排序还原自上而下、从左到右的阅读顺序
     * </p>
     *
     * @param file
     *            待识别的图像文件
     * @param token
     *            调用凭证，透传给阿里云服务校验
     * @return 形如 {"results": [{text, bbox, confidence}, ...]} 的识别结果
     */
    public Map<String, Object> recognize(MultipartFile file, String token) throws IOException {
        aliyunOcrService.verifyCallbackToken(token);

        JsonNode data = aliyunOcrService.recognize(file.getBytes());

        List<Map<String, Object>> results = new ArrayList<>();
        JsonNode subImages = pathAny(data, "SubImages", "subImages");
        if (subImages.isArray()) {
            for (JsonNode subImage : subImages) {
                appendBlockResults(results,
                    pathAny(pathAny(subImage, "BlockInfo", "blockInfo"), "BlockDetails", "blockDetails"));
            }
        }

        if (results.isEmpty()) {
            appendContentFallback(results, textAny(data, "Content", "content"));
        }

        results.sort(Comparator
            .comparingDouble((Map<String, Object> item) -> ((List<?>)item.get("bbox")).isEmpty() ? 0.0
                : toDouble(((List<?>)item.get("bbox")).get(1)))
            .thenComparingDouble(
                item -> ((List<?>)item.get("bbox")).isEmpty() ? 0.0 : toDouble(((List<?>)item.get("bbox")).get(0))));

        return Map.of("results", results);
    }

    /**
     * 尝试将数值转为 double，失败时返回 0.0
     * 
     * @param value
     * @return
     */
    private double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    /**
     * 兜底策略：阿里云未返回块级明细时，将整段 Content 文本按行拆分为独立的识别结果， bbox 置为 [0,0,0,0] 占位、置信度固定 1.0，保证下游仍可拿到可用文本
     */
    private void appendContentFallback(List<Map<String, Object>> results, String content) {
        if (StringUtils.isBlank(content)) {
            return;
        }

        for (String line : content.split("\\R+")) {
            String text = line.trim();
            if (!text.isEmpty()) {
                results.add(result(text, List.of(0.0, 0.0, 0.0, 0.0), 1.0));
            }
        }
    }

    /**
     * 逐块转换识别明细：提取文本内容，将多边形坐标收敛为外接矩形 bbox，置信度归一化后输出
     */
    private void appendBlockResults(List<Map<String, Object>> results, JsonNode blockDetails) {
        if (!blockDetails.isArray()) {
            return;
        }

        for (JsonNode block : blockDetails) {
            String text = textAny(block, "BlockContent", "blockContent").trim();
            if (text.isEmpty()) {
                continue;
            }

            List<Double> bbox = pointsToBbox(pathAny(block, "BlockPoints", "blockPoints"));
            double confidence = normalizeConfidence(numberAny(block, 100.0, "BlockConfidence", "blockConfidence"));

            results.add(result(text, bbox, confidence));
        }
    }

    /**
     * 构建 LiteParse 标准结果项，置信度钳制到 [0,1] 区间防止上游异常值
     */
    private Map<String, Object> result(String text, List<Double> bbox, double confidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", text);
        result.put("bbox", bbox);
        result.put("confidence", Math.max(0.0, Math.min(1.0, confidence)));

        return result;
    }

    /**
     * 置信度归一化：阿里云返回百分制（0-100），统一换算为 0-1 小数
     */
    private double normalizeConfidence(double confidence) {
        return confidence > 1.0 ? confidence / 100.0 : confidence;
    }

    /**
     * 将多边形顶点集收敛为外接矩形 [minX, minY, maxX, maxY]，无有效顶点时返回零矩形占位
     */
    private List<Double> pointsToBbox(JsonNode points) {
        if (!points.isArray() || points.isEmpty()) {
            return List.of(0.0, 0.0, 0.0, 0.0);
        }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = 0.0;
        double maxY = 0.0;
        for (JsonNode point : points) {
            double x = numberAny(point, 0.0, "X", "x");
            double y = numberAny(point, 0.0, "Y", "y");
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        if (minX == Double.MAX_VALUE || minY == Double.MAX_VALUE) {
            return List.of(0.0, 0.0, 0.0, 0.0);
        }

        return List.of(minX, minY, maxX, maxY);
    }

    /**
     * 按候选字段名依次取数值，全部缺失时返回默认值
     */
    private double numberAny(JsonNode node, double defaultValue, String... fieldNames) {
        JsonNode value = pathAny(node, fieldNames);

        return value.isMissingNode() || value.isNull() ? defaultValue : value.asDouble(defaultValue);
    }

    /**
     * 按候选字段名依次取文本，全部缺失时返回空串
     */
    private String textAny(JsonNode node, String... fieldNames) {
        JsonNode value = pathAny(node, fieldNames);

        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    /**
     * 按候选字段名依次取子节点：阿里云不同接口版本返回的 JSON 可能使用 PascalCase 或 camelCase 命名（如 BlockContent/blockContent），传入两种写法可同时兼容
     */
    private JsonNode pathAny(JsonNode node, String... fieldNames) {
        if (Objects.isNull(node) || node.isMissingNode() || node.isNull()) {
            return MissingNode.getInstance();
        }

        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }

        return MissingNode.getInstance();
    }
}
