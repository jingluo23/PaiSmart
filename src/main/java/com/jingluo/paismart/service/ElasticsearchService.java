package com.jingluo.paismart.service;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.entity.EsDocument;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 9:03
 * @Desc: Elasticsearch 索引服务，提供知识库索引的按文档删除与批量写入能力
 */
@Slf4j
@Service
public class ElasticsearchService {

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    /**
     * 按文件 MD5 删除该文档在知识库索引中的全部记录
     *
     * @param fileMd5
     *            文件 MD5
     * @throws RuntimeException
     *             删除请求执行失败时抛出
     */
    public void deleteByFileMd5(String fileMd5) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest
                .of(d -> d.index("knowledge_base").query(q -> q.term(t -> t.field("fileMd5").value(fileMd5))));

            elasticsearchClient.deleteByQuery(request);
        } catch (Exception e) {
            throw new RuntimeException("删除文档失败", e);
        }
    }

    /**
     * 将文档列表批量写入 knowledge_base 索引（以文档自带 ID 作为 _id）； 任一条目失败时记录明细日志并整体抛出异常
     *
     * @param documents
     *            待写入的文档列表
     * @throws RuntimeException
     *             批量请求异常或存在失败条目时抛出
     */
    public void bulkIndex(List<EsDocument> documents) {
        try {
            // 将文档列表转换为批量操作列表，每个文档都对应一个索引操作
            List<BulkOperation> bulkOperations = documents.stream()
                .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                    // 指定索引名称
                    .index("knowledge_base")
                    // 使用文档的ID作为Elasticsearch中的文档ID
                    .id(doc.getId())
                    // 将文档对象作为数据源
                    .document(doc))))
                .toList();

            // 创建BulkRequest对象，并将批量操作列表添加到请求中
            BulkRequest request = BulkRequest.of(b -> b.operations(bulkOperations));

            // 执行批量索引操作
            BulkResponse response = elasticsearchClient.bulk(request);

            // 检查响应结果
            if (response.errors()) {
                for (BulkResponseItem item : response.items()) {
                    if (Objects.nonNull(item.error())) {
                        log.warn("文档索引失败 - ID: {}, 错误: {}", item.id(), item.error().reason());
                    }
                }

                throw new RuntimeException("批量索引部分失败，请检查日志");
            } else {
                log.warn("批量索引成功完成，文档数量: {}", documents.size());
            }
        } catch (Exception e) {
            log.warn("批量索引失败，文档数量: {}", documents.size(), e);

            // 如果发生异常，抛出运行时异常，表明批量索引失败
            throw new RuntimeException("批量索引失败", e);
        }
    }

    /**
     * 按文件 MD5 统计该文档在知识库索引中的记录条数
     *
     * @param fileMd5
     *            文件 MD5
     * @return 索引命中的文档数量
     * @throws RuntimeException
     *             统计请求执行失败时抛出
     */
    public long countByFileMd5(String fileMd5) {
        try {
            CountResponse response = elasticsearchClient
                .count(c -> c.index("knowledge_base").query(q -> q.term(t -> t.field("fileMd5").value(fileMd5))));

            return response.count();
        } catch (Exception e) {
            throw new RuntimeException("统计文档失败", e);
        }
    }
}
