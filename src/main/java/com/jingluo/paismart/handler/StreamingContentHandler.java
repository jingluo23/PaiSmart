package com.jingluo.paismart.handler;

import java.util.List;

import org.apache.tika.sax.BodyContentHandler;

import com.jingluo.paismart.service.ParseService;

/**
 * @Author: xuht32
 * @Date: 2026/9/17 17:37
 * @Desc: Tika 流式解析内容处理器：边解析边按父块粒度切分并保存子块，避免大文件全量驻留内存。 非 Spring Bean，每次解析调用按次创建，依赖由 ParseService 通过构造器传入
 */
public class StreamingContentHandler extends BodyContentHandler {

    private final ParseService parseService;

    private final int chunkSize;

    private final int parentChunkSize;

    private final StringBuilder buffer = new StringBuilder();

    private final String fileMd5;

    private final String userId;

    private final String orgTag;

    private final boolean isPublic;

    private int savedChunkCount = 0;

    public StreamingContentHandler(ParseService parseService, int chunkSize, int parentChunkSize, String fileMd5,
        String userId, String orgTag, boolean isPublic) {
        // 禁用Tika的内部写入限制，我们自己管理缓冲区
        super(-1);
        this.parseService = parseService;
        this.chunkSize = chunkSize;
        this.parentChunkSize = parentChunkSize;
        this.fileMd5 = fileMd5;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        buffer.append(ch, start, length);
        if (buffer.length() >= parentChunkSize) {
            processParentChunk();
        }
    }

    @Override
    public void endDocument() {
        // 处理文档末尾剩余的最后一部分内容
        if (buffer.length() > 0) {
            processParentChunk();
        }
    }

    private void processParentChunk() {
        String parentChunkText = buffer.toString();

        // 1. 将父块分割成更小的、有语义的子切片
        List<String> childChunks = parseService.splitTextIntoChunksWithSemantics(parentChunkText, chunkSize);

        // 2. 将子切片批量保存到数据库
        this.savedChunkCount =
            parseService.saveChildChunks(fileMd5, childChunks, userId, orgTag, isPublic, this.savedChunkCount, null);

        // 3. 清空缓冲区，为下一个父块做准备
        buffer.setLength(0);
    }
}
