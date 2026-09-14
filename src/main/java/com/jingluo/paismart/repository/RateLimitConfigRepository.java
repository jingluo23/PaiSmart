package com.jingluo.paismart.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingluo.paismart.model.RateLimitConfig;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 16:03
 * @Desc: 限流配置类
 */
public interface RateLimitConfigRepository extends JpaRepository<RateLimitConfig, String> {}
