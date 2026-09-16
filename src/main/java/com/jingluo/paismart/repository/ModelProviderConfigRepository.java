package com.jingluo.paismart.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.model.ModelProviderConfig;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 17:03
 * @Desc: 模型提供者配置仓储接口
 */
@Repository
public interface ModelProviderConfigRepository extends JpaRepository<ModelProviderConfig, Long> {

    /**
     * 根据配置范围查询模型提供者配置
     *
     * @param configScope
     *            配置范围
     * @return 模型提供者配置列表
     */
    List<ModelProviderConfig> findByConfigScopeOrderByProviderCodeAsc(String configScope);

    /**
     * 根据配置范围和提供者代码查询模型提供者配置
     *
     * @param configScope
     *            配置范围
     * @param providerCode
     *            提供者代码
     * @return 模型提供者配置
     */
    Optional<ModelProviderConfig> findByConfigScopeAndProviderCode(String configScope, String providerCode);
}
