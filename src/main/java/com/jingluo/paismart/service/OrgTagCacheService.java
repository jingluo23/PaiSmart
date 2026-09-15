package com.jingluo.paismart.service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

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
}
