package com.jingluo.paismart.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.model.OrganizationTag;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 16:29
 * @Desc: 组织标签数据访问接口
 */
@Repository
public interface OrganizationTagRepository extends JpaRepository<OrganizationTag, String> {

    /**
     * 根据标签 ID 查询组织标签
     *
     * @param tagId
     * @return
     */
    Optional<OrganizationTag> findByTagId(String tagId);

    /**
     * 按 tagId 集合批量查询组织标签，供用户列表页一次性取整页涉及的标签
     *
     * @param tagIds
     *            标签 ID 集合
     * @return 匹配的标签列表
     */
    List<OrganizationTag> findByTagIdIn(Collection<String> tagIds);

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
