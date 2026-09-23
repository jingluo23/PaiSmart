package com.jingluo.paismart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.jingluo.paismart.domain.response.DualWindowLimit;
import com.jingluo.paismart.domain.response.TokenBudgetLimit;
import com.jingluo.paismart.domain.response.WindowLimit;

import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 15:46
 * @Desc: 限制配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    /**
     * 注册限制
     */
    private WindowLimit register = new WindowLimit(20, 600);

    /**
     * 登录限制
     */
    private WindowLimit login = new WindowLimit(30, 60);

    /**
     * 聊天消息限制
     */
    private WindowLimit chatMessage = new WindowLimit(30, 60);

    /**
     * LLM 全局令牌限制
     */
    private TokenBudgetLimit llmGlobalToken = new TokenBudgetLimit(120_000L, 60L, 8_000_000L, 86400L);

    /**
     * embedding 上传限制
     */
    private TokenBudgetLimit embeddingUploadToken = new TokenBudgetLimit(200_000L, 60L, 20_000_000L, 86400L);

    /**
     * embedding 查询限制
     */
    private DualWindowLimit embeddingQueryRequest = new DualWindowLimit(60L, 60L, 5000L, 86400L);

    /**
     * embedding 全局令牌限制
     */
    private TokenBudgetLimit embeddingQueryGlobalToken = new TokenBudgetLimit(60_000L, 60L, 4_000_000L, 86400L);
}
