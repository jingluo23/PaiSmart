package com.jingluo.paismart.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.client.EmbeddingClient;
import com.jingluo.paismart.entity.EsDocument;
import com.jingluo.paismart.entity.SearchResult;
import com.jingluo.paismart.enums.UsageType;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.FileUpload;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.FileUploadRepository;
import com.jingluo.paismart.repository.UserRepository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * @author: 鲸落
 * @Date: 2026/9/18 16:55
 * @Desc: 混合检索服务：KNN 向量召回 + BM25 关键词过滤与重排序， 并叠加用户/组织/公开三级数据权限过滤，失败时降级为纯文本检索
 */
@Slf4j
@Service
public class HybridSearchService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    /**
     * 带数据权限的混合检索：向量召回候选分块后叠加 BM25 重排序， 权限过滤匹配"本人文档 / 公开文档 / 所属组织文档（含层级）"任一条件； 向量生成失败或整体异常时降级为纯文本检索
     *
     * @param query
     *            检索关键词
     * @param userId
     *            用户 ID 或用户名，用于权限过滤
     * @param topK
     *            返回结果条数上限
     * @return 命中的分块结果，文件名已补齐
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        try {
            // 获取用户有效的组织标签（包含层级关系）
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);

            // 获取用户的数据库ID用于权限过滤
            String userDbId = getUserDbId(userId);

            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query, userId);

            // 如果向量生成失败，仅使用文本匹配
            if (CollectionUtils.isEmpty(queryVector)) {
                return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
            }

            SearchResponse<EsDocument> response = elasticsearchClient.search(s -> {
                s.index("knowledge_base");
                // KNN 召回
                // KNN 召回窗口
                int recallK = topK * 30;
                s.knn(kn -> kn.field("vector").queryVector(queryVector).k(recallK).numCandidates(recallK));
                // 必须命中关键词 + 权限过滤
                s.query(q -> q.bool(b -> b.must(mst -> mst.match(m -> m.field("textContent").query(query)))
                    .filter(f -> f.bool(bf -> bf
                        // 条件1: 用户可访问自己的文档
                        .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                        // 条件2: 公开文档
                        .should(s2 -> s2.term(t -> t.field("public").value(true)))
                        // 条件3: 组织标签
                        .should(s3 -> {
                            if (userEffectiveTags.isEmpty()) {
                                return s3.matchNone(mn -> mn);
                            } else if (userEffectiveTags.size() == 1) {
                                return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                            } else {
                                return s3.bool(inner -> {
                                    userEffectiveTags.forEach(
                                        tag -> inner.should(sh2 -> sh2.term(t -> t.field("orgTag").value(tag))));

                                    return inner;
                                });
                            }
                        })))));

                // 第二阶段 BM25 rescore
                s.rescore(r -> r.windowSize(recallK).query(rq -> rq
                    // 保留部分 KNN 分
                    .queryWeight(0.2d)
                    // BM25 主导
                    .rescoreQueryWeight(1.0d)
                    .query(rqq -> rqq.match(m -> m.field("textContent").query(query).operator(Operator.And)))));
                s.size(topK);
                return s;
            }, EsDocument.class);

            List<SearchResult> results = response.hits().hits().stream().map(hit -> {
                assert Objects.nonNull(hit.source());

                return new SearchResult(hit.source().getFileMd5(), hit.source().getChunkId(),
                    hit.source().getTextContent(), hit.score(), hit.source().getUserId(), hit.source().getOrgTag(),
                    hit.source().isPublic(), null, hit.source().getPageNumber(), hit.source().getAnchorText(), "HYBRID",
                    hit.source().getTextContent());
            }).toList();

            attachFileNames(results);

            return results;
        } catch (Exception e) {
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                return textOnlySearchWithPermission(query, getUserDbId(userId), getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                return Collections.emptyList();
            }
        }
    }

    /**
     * 带数据权限的纯文本检索：向量服务不可用时的降级方案， 查询结构与混合检索一致但仅依赖 BM25 相关性，并设置最低得分阈值过滤噪声
     *
     * @param query
     *            检索关键词
     * @param userDbId
     *            用户数据库 ID，用于权限过滤
     * @param userEffectiveTags
     *            用户有效组织标签集合
     * @param topK
     *            返回结果条数上限
     * @return 命中的分块结果，异常时返回空列表
     */
    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId,
        List<String> userEffectiveTags, int topK) {
        try {
            SearchResponse<EsDocument> response = elasticsearchClient.search(s -> s.index("knowledge_base")
                .query(q -> q.bool(b -> b
                    // 匹配内容相关性
                    .must(m -> m.match(ma -> ma.field("textContent").query(query)))
                    // 权限过滤
                    .filter(f -> f.bool(bf -> bf
                        // 条件1: 用户可以访问自己的文档
                        .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                        // 条件2: 用户可以访问公开的文档
                        .should(s2 -> s2.term(t -> t.field("public").value(true)))
                        // 条件3: 用户可以访问其所属组织的文档（包含层级关系）
                        .should(s3 -> {
                            if (userEffectiveTags.isEmpty()) {
                                return s3.matchNone(mn -> mn);
                            } else if (userEffectiveTags.size() == 1) {
                                // 单个标签使用 term 查询
                                return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                            } else {
                                // 多个标签使用 bool should 组合多个 term 查询
                                return s3.bool(innerBool -> {
                                    userEffectiveTags.forEach(
                                        tag -> innerBool.should(sh -> sh.term(t -> t.field("orgTag").value(tag))));
                                    return innerBool;
                                });
                            }
                        })))))
                .minScore(0.3d).size(topK), EsDocument.class);

            List<SearchResult> results = response.hits().hits().stream().map(hit -> {
                assert Objects.nonNull(hit.source());

                return new SearchResult(hit.source().getFileMd5(), hit.source().getChunkId(),
                    hit.source().getTextContent(), hit.score(), hit.source().getUserId(), hit.source().getOrgTag(),
                    hit.source().isPublic(), null, hit.source().getPageNumber(), hit.source().getAnchorText(),
                    "TEXT_ONLY", hit.source().getTextContent());
            }).toList();

            attachFileNames(results);

            return results;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 批量补齐结果对应的原始文件名：ES 中仅存文件指纹，需反查上传记录； 补充失败仅记录日志，不影响主流程返回
     *
     * @param results
     *            待补齐的检索结果列表
     */
    private void attachFileNames(List<SearchResult> results) {
        if (CollectionUtils.isEmpty(results)) {
            return;
        }

        try {
            // 收集所有唯一的 fileMd5
            Set<String> md5Set = results.stream().map(SearchResult::getFileMd5).collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new java.util.ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream().collect(
                Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName, (existing, replacement) -> existing));
            // 填充文件名
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            log.warn("补充文件名失败", e);
        }
    }

    /**
     * 将文本转换为查询向量：失败时返回 null，由调用方降级为纯文本检索
     *
     * @param text
     *            待向量化的文本
     * @param requesterId
     *            请求者标识，用于 Embedding 配额计量
     * @return 向量（以 List&lt;Float&gt; 适配 ES 客户端入参），失败返回 null
     */
    private List<Float> embedToVectorList(String text, String requesterId) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text), requesterId, UsageType.QUERY);
            if (CollectionUtils.isEmpty(vecs)) {
                return null;
            }

            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }

            return list;
        } catch (Exception e) {
            log.warn("生成向量失败", e);

            return null;
        }
    }

    /**
     * 将用户 ID 或用户名统一解析为数据库 ID：数字串直接视为 ID 查询， 否则按用户名反查；解析失败抛出运行时异常中断本次检索
     *
     * @param userId
     *            用户 ID 或用户名
     * @return 用户的数据库 ID 字符串
     */
    private String getUserDbId(String userId) {
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);

                userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("未找到ID对应的用户: " + userId, HttpStatus.NOT_FOUND));

                // 如果输入已经是数字ID，直接返回
                return userIdLong.toString();
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("未找到用户: " + userId, HttpStatus.NOT_FOUND));

                // 返回用户的数据库ID
                return user.getId().toString();
            }
        } catch (Exception e) {
            log.warn("获取用户数据库ID失败: {}", e.getMessage(), e);

            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    /**
     * 获取用户的有效组织标签集合（含全部层级父标签）， 供 ES 权限过滤使用；解析失败返回空列表以收窄可见范围
     *
     * @param userId
     *            用户 ID 或用户名
     * @return 有效组织标签集合，失败时为空列表
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        try {
            // 获取用户名
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);

                user = userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("通过 ID 未找到用户: " + userId, HttpStatus.NOT_FOUND));
            } catch (NumberFormatException e) {
                // 如果userId不是数字格式，则假设它就是username
                user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("未找到用户: " + userId, HttpStatus.NOT_FOUND));
            }

            // 通过orgTagCacheService获取用户的有效标签集合
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception e) {
            // 返回空列表作为默认值
            return Collections.emptyList();
        }
    }

    /**
     * 无数据权限的混合检索：仅匹配关键词相关性，不区分归属， 面向匿名或公开内容场景；向量生成失败时降级为纯文本检索
     *
     * @param query
     *            检索关键词
     * @param topK
     *            返回结果条数上限
     * @return 命中的分块结果
     */
    public List<SearchResult> search(String query, int topK) {
        try {
            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(query, "system");

            // 如果向量生成失败，仅使用文本匹配
            if (CollectionUtils.isEmpty(queryVector)) {
                return textOnlySearch(query, topK);
            }

            SearchResponse<EsDocument> response = elasticsearchClient.search(s -> {
                s.index("knowledge_base");
                int recallK = topK * 30;
                s.knn(kn -> kn.field("vector").queryVector(queryVector).k(recallK).numCandidates(recallK));

                // 过滤仅保留包含关键词的文本
                s.query(q -> q.match(m -> m.field("textContent").query(query)));

                // rescore BM25
                s.rescore(r -> r.windowSize(recallK).query(rq -> rq.queryWeight(0.2d).rescoreQueryWeight(1.0d)
                    .query(rqq -> rqq.match(m -> m.field("textContent").query(query).operator(Operator.And)))));
                s.size(topK);
                return s;
            }, EsDocument.class);

            return response.hits().hits().stream().map(hit -> {
                assert Objects.nonNull(hit.source());

                return new SearchResult(hit.source().getFileMd5(), hit.source().getChunkId(),
                    hit.source().getTextContent(), hit.score(), null, null, false, null, hit.source().getPageNumber(),
                    hit.source().getAnchorText(), "HYBRID", hit.source().getTextContent());
            }).toList();
        } catch (Exception e) {
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                return textOnlySearch(query, topK);
            } catch (Exception fallbackError) {
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    /**
     * 无数据权限的纯文本检索：search 的降级方案，仅按 BM25 相关性排序
     *
     * @param query
     *            检索关键词
     * @param topK
     *            返回结果条数上限
     * @return 命中的分块结果
     */
    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = elasticsearchClient.search(
            s -> s.index("knowledge_base").query(q -> q.match(m -> m.field("textContent").query(query))).size(topK),
            EsDocument.class);

        return response.hits().hits().stream().map(hit -> {
            assert hit.source() != null;
            return new SearchResult(hit.source().getFileMd5(), hit.source().getChunkId(), hit.source().getTextContent(),
                hit.score(), null, null, false, null, hit.source().getPageNumber(), hit.source().getAnchorText(),
                "TEXT_ONLY", hit.source().getTextContent());
        }).toList();
    }
}
