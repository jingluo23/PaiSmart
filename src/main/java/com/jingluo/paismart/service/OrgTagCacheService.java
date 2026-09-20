package com.jingluo.paismart.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.jingluo.paismart.model.OrganizationTag;
import com.jingluo.paismart.repository.OrganizationTagRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 16:40
 * @Desc: 组织标签缓存服务，基于 Redis 缓存用户的组织标签、主组织及有效标签，减少高频查询对数据库的压力
 */
@Slf4j
@Service
public class OrgTagCacheService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    /**
     * 用户有效标签缓存 key 前缀
     */
    private static final String USER_EFFECTIVE_TAGS_KEY_PREFIX = "user:effective_org_tags:";

    /**
     * 用户组织标签列表缓存 key 前缀
     */
    private static final String USER_ORG_TAGS_KEY_PREFIX = "user:org_tags:";

    /**
     * 用户主组织缓存 key 前缀
     */
    private static final String USER_PRIMARY_ORG_KEY_PREFIX = "user:primary_org:";

    /**
     * 缓存过期时间（小时）
     */
    private static final long CACHE_TTL_HOURS = 24;

    /**
     * 默认组织标签，保证所有用户在无任何组织归属时仍有兜底可见范围
     */
    private static final String DEFAULT_ORG_TAG = "DEFAULT";

    /**
     * 使所有用户的有效标签缓存失效，在标签层级或内容发生变更时调用，避免缓存与数据库不一致
     */
    public void invalidateAllEffectiveTagsCache() {
        try {
            Set<String> keys = redisTemplate.keys(USER_EFFECTIVE_TAGS_KEY_PREFIX + "*");
            if (!CollectionUtils.isEmpty(keys)) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("无法使有效的组织标签缓存失效", e);
        }
    }

    /**
     * 删除指定用户的组织标签列表缓存与主组织缓存
     *
     * @param username
     *            用户名
     */
    public void deleteUserOrgTagsCache(String username) {
        try {
            String orgTagsKey = USER_ORG_TAGS_KEY_PREFIX + username;
            String primaryOrgKey = USER_PRIMARY_ORG_KEY_PREFIX + username;
            redisTemplate.delete(orgTagsKey);
            redisTemplate.delete(primaryOrgKey);
        } catch (Exception e) {
            log.warn("无法删除用户的组织标签缓存: {}", username, e);
        }
    }

    /**
     * 缓存用户的组织标签列表
     *
     * @param username
     *            用户名
     * @param orgTags
     *            组织标签 ID 列表
     */
    public void cacheUserOrgTags(String username, List<String> orgTags) {
        try {
            String key = USER_ORG_TAGS_KEY_PREFIX + username;
            redisTemplate.opsForList().rightPushAll(key, orgTags.toArray());
            redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("无法为用户缓存组织标签: {}", username, e);
        }
    }

    /**
     * 删除指定用户的有效标签缓存
     *
     * @param username
     *            用户名
     */
    public void deleteUserEffectiveTagsCache(String username) {
        try {
            String key = USER_EFFECTIVE_TAGS_KEY_PREFIX + username;
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("无法删除用户的有效组织标签缓存: {}", username, e);
        }
    }

    /**
     * 缓存用户的主组织
     *
     * @param username
     *            用户名
     * @param primaryOrg
     *            主组织标签 ID
     */
    public void cacheUserPrimaryOrg(String username, String primaryOrg) {
        try {
            String key = USER_PRIMARY_ORG_KEY_PREFIX + username;
            redisTemplate.opsForValue().set(key, primaryOrg);
            redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("无法为用户缓存主组织: {}", username, e);
        }
    }

    /**
     * 从缓存读取用户的组织标签列表
     *
     * @param username
     *            用户名
     * @return 组织标签 ID 列表，缓存未命中或访问异常时返回 null
     */
    public List<String> getUserOrgTags(String username) {
        try {
            String key = USER_ORG_TAGS_KEY_PREFIX + username;
            List<Object> result = redisTemplate.opsForList().range(key, 0, -1);
            if (!CollectionUtils.isEmpty(result)) {
                return result.stream().map(obj -> (String)obj).toList();
            }
        } catch (Exception e) {
            log.warn("无法获取用户的组织标签: {}", username, e);
        }

        return null;
    }

    /**
     * 从缓存读取用户的主组织
     *
     * @param username
     *            用户名
     * @return 主组织标签 ID，缓存未命中或访问异常时返回 null
     */
    public String getUserPrimaryOrg(String username) {
        try {
            String key = USER_PRIMARY_ORG_KEY_PREFIX + username;

            return (String)redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("无法获取用户的主要组织: {}", username, e);

            return null;
        }
    }

    /**
     * 获取用户的有效组织标签集合：缓存未命中时以用户标签为起点逐级上溯父标签， 并保证默认标签始终包含，结果回写缓存；异常时仅返回默认标签
     *
     * @param username
     *            用户名
     * @return 有效组织标签集合（含全部层级父标签与默认标签）
     */
    public List<String> getUserEffectiveOrgTags(String username) {
        try {
            // 从缓存获取
            String cacheKey = USER_EFFECTIVE_TAGS_KEY_PREFIX + username;
            List<Object> cachedTags = redisTemplate.opsForList().range(cacheKey, 0, -1);

            if (!CollectionUtils.isEmpty(cachedTags)) {
                List<String> effectiveTags = cachedTags.stream().map(Object::toString).collect(Collectors.toList());

                // 确保默认标签在结果中（从缓存读取的情况）
                if (!effectiveTags.contains(DEFAULT_ORG_TAG)) {
                    effectiveTags.add(DEFAULT_ORG_TAG);
                }

                return effectiveTags;
            }

            // 缓存未命中，计算有效标签集合
            List<String> userTags = getUserOrgTags(username);
            Set<String> allEffectiveTags = new HashSet<>();

            // 如果用户有标签，添加到集合中并查找父标签
            if (!CollectionUtils.isEmpty(userTags)) {
                allEffectiveTags.addAll(userTags);

                // 查找所有父标签
                for (String tagId : userTags) {
                    collectParentTags(tagId, allEffectiveTags);
                }
            }

            // 确保默认标签在结果中
            allEffectiveTags.add(DEFAULT_ORG_TAG);

            List<String> result = new ArrayList<>(allEffectiveTags);

            // 缓存结果
            if (!result.isEmpty()) {
                redisTemplate.opsForList().rightPushAll(cacheKey, result.toArray());
                redisTemplate.expire(cacheKey, CACHE_TTL_HOURS, TimeUnit.HOURS);
            }

            return result;
        } catch (Exception e) {
            // 错误情况下至少返回默认标签
            return Collections.singletonList(DEFAULT_ORG_TAG);
        }
    }

    /**
     * 递归收集指定标签的全部父级标签：父标签缺失或查询异常时静默终止， 不阻断其余标签的处理
     *
     * @param tagId
     *            起始标签 ID
     * @param result
     *            父级标签收集结果
     */
    private void collectParentTags(String tagId, Set<String> result) {
        try {
            OrganizationTag tag = organizationTagRepository.findByTagId(tagId).orElse(null);
            if (Objects.nonNull(tag) && StringUtils.isNotBlank(tag.getParentTag())) {
                String parentTagId = tag.getParentTag();
                result.add(parentTagId);

                collectParentTags(parentTagId, result);
            }
        } catch (Exception e) {
            log.warn("收集标签的父级标签时出错: {}", tagId, e);
        }
    }
}
