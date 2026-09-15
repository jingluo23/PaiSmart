package com.jingluo.paismart.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingluo.paismart.model.OrganizationTag;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 16:29
 * @Desc: 组织标签数据访问接口
 */
public interface OrganizationTagRepository extends JpaRepository<OrganizationTag, String> {

    /**
     * 根据标签 ID 查询组织标签
     *
     * @param tagId
     * @return
     */
    Optional<OrganizationTag> findByTagId(String tagId);

    /**
     * 判断指定标签 ID 是否已存在
     *
     * @param tagId
     * @return
     */
    boolean existsByTagId(String tagId);

    /**
     * 查询指定父标签下的所有子标签，parentTag 为 null 时返回根标签
     *
     * @param parentTag
     * @return
     */
    List<OrganizationTag> findByParentTag(String parentTag);
}
