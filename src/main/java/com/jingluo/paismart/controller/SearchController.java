package com.jingluo.paismart.controller;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jingluo.paismart.domain.response.ResponseResult;
import com.jingluo.paismart.entity.SearchResult;
import com.jingluo.paismart.service.HybridSearchService;

/**
 * @Author: 鲸落
 * @Date: 2026/9/18 16:54
 * @Desc: 搜索接口控制器，对外提供知识库混合检索入口
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    @Autowired
    private HybridSearchService hybridSearchService;

    /**
     * 混合检索接口：KNN 向量召回 + BM25 重排序，登录用户附带数据权限过滤， 匿名请求仅检索公开内容
     *
     * @param query
     *            检索关键词
     * @param topK
     *            返回结果条数上限，默认 10
     * @param userId
     *            认证过滤器写入的当前用户 ID，匿名访问时为空
     * @return 检索结果列表
     */
    @GetMapping("/hybrid")
    public ResponseResult hybridSearch(@RequestParam String query, @RequestParam(defaultValue = "10") int topK,
        @RequestAttribute(value = "userId", required = false) String userId) {
        List<SearchResult> results;
        if (StringUtils.isNotBlank(userId)) {
            // 如果有用户ID，使用带权限的搜索
            results = hybridSearchService.searchWithPermission(query, userId, topK);
        } else {
            // 如果没有用户ID，使用普通搜索（仅公开内容）
            results = hybridSearchService.search(query, topK);
        }

        return ResponseResult.success(results);
    }
}
