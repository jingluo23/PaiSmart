package com.jingluo.paismart.handler;

import java.util.List;

import org.apache.tika.sax.BodyContentHandler;

import com.jingluo.paismart.domain.response.EmbeddingEstimate;
import com.jingluo.paismart.service.ParseService;
import com.jingluo.paismart.service.UsageQuotaService;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 16:30
 * @Desc: Tika 流式解析内容处理器：边解析边按父块粒度估算 Embedding Token 数与分块数，避免大文件全量驻留内存。 非 Spring Bean，每次估算调用按次创建，依赖由 ParseService 通过构造器传入
 */
public class StreamingEstimateHandler extends BodyContentHandler {

    private final ParseService parseService;

    private final UsageQuotaService usageQuotaService;

    /**
     * 父块阈值：缓冲内容达到该大小即触发一次估算，默认 1MB
     */
    private final int parentChunkSize;

    /**
     * 向量化子分块大小（字符数），与解析服务保持一致
     */
    private final int chunkSize;

    /**
     * 累积待估算的文本缓冲区
     */
    private final StringBuilder buffer = new StringBuilder();

    /**
     * 已累计估算的 Token 数
     */
    private long estimatedTokens = 0L;

    /**
     * 已累计估算的分块数
     */
    private int estimatedChunkCount = 0;

    /**
     * 传入 -1 取消 BodyContentHandler 默认的写入字符上限， 保证大文档内容不会因超出截断阈值而丢失
     */
    public StreamingEstimateHandler(ParseService parseService, UsageQuotaService usageQuotaService, int chunkSize,
        int parentChunkSize) {
        super(-1);
        this.parseService = parseService;
        this.usageQuotaService = usageQuotaService;
        this.chunkSize = chunkSize;
        this.parentChunkSize = parentChunkSize;
    }

    /**
     * 收集解析出的字符内容到缓冲区，达到父块阈值时触发一次估算
     */
    @Override
    public void characters(char[] ch, int start, int length) {
        buffer.append(ch, start, length);
        if (buffer.length() >= parentChunkSize) {
            processParentChunk();
        }
    }

    /**
     * 文档解析结束时处理缓冲区中的剩余内容，保证估算完整
     */
    @Override
    public void endDocument() {
        if (buffer.length() > 0) {
            processParentChunk();
        }
    }

    /**
     * 将缓冲区内容按语义切分为子分块并累计 Token 估算值，随后清空缓冲区继续接收
     */
    private void processParentChunk() {
        List<String> childChunks = parseService.splitTextIntoChunksWithSemantics(buffer.toString(), chunkSize);
        estimatedChunkCount += childChunks.size();
        estimatedTokens += usageQuotaService.estimateEmbeddingTokens(childChunks);
        buffer.setLength(0);
    }

    /**
     * 获取当前累计的估算结果快照
     *
     * @return 预估 Token 数与分块数
     */
    public EmbeddingEstimate snapshot() {
        return new EmbeddingEstimate(estimatedTokens, estimatedChunkCount);
    }
}
