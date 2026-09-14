package com.jingluo.paismart.service;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.config.RateLimitProperties;
import com.jingluo.paismart.domain.response.DualWindowLimitView;
import com.jingluo.paismart.domain.response.RateLimitSettingsView;
import com.jingluo.paismart.domain.response.TokenBudgetView;
import com.jingluo.paismart.domain.response.WindowLimitView;
import com.jingluo.paismart.model.RateLimitConfig;
import com.jingluo.paismart.repository.RateLimitConfigRepository;

import io.micrometer.common.util.StringUtils;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 15:35
 * @Desc:
 */
@Service
public class RateLimitConfigService {

    @Autowired
    private RateLimitProperties properties;

    @Autowired
    private RateLimitConfigRepository rateLimitConfigRepository;

    private static final String CHAT_MESSAGE = "chat-message";

    private static final String LLM_GLOBAL_TOKEN = "llm-global-token";

    private static final String EMBEDDING_UPLOAD_TOKEN = "embedding-upload-token";

    private static final String EMBEDDING_QUERY_REQUEST = "embedding-query-request";

    private static final String EMBEDDING_QUERY_GLOBAL_TOKEN = "embedding-query-global-token";

    /**
     * 获取当前限流设置
     *
     * @return
     */
    public RateLimitSettingsView getCurrentSettings() {
        RateLimitSettingsView rateLimitSettingsView = buildDefaultSettings();

        return mergeOverrides(rateLimitSettingsView, rateLimitConfigRepository.findAll());
    }

    /**
     * 合并限流设置
     *
     * @param defaults
     * @param configs
     * @return
     */
    private RateLimitSettingsView mergeOverrides(RateLimitSettingsView defaults, List<RateLimitConfig> configs) {
        WindowLimitView chatMessage = defaults.getChatMessage();
        TokenBudgetView llmGlobalToken = defaults.getLlmGlobalToken();
        TokenBudgetView embeddingUploadToken = defaults.getEmbeddingUploadToken();
        DualWindowLimitView embeddingQueryRequest = defaults.getEmbeddingQueryRequest();
        TokenBudgetView embeddingQueryGlobalToken = defaults.getEmbeddingQueryGlobalToken();

        for (RateLimitConfig config : configs) {
            if (Objects.isNull(config) || StringUtils.isBlank(config.getConfigKey())) {
                continue;
            }

            switch (config.getConfigKey()) {
                case CHAT_MESSAGE -> {
                    if (Objects.nonNull(config.getSingleMax()) && Objects.nonNull(config.getSingleWindowSeconds())) {
                        chatMessage = new WindowLimitView(config.getSingleMax(), config.getSingleWindowSeconds());
                    }
                }
                case LLM_GLOBAL_TOKEN -> {
                    if (Objects.nonNull(config.getMinuteMax()) && Objects.nonNull(config.getMinuteWindowSeconds())
                        && Objects.nonNull(config.getDayMax()) && Objects.nonNull(config.getDayWindowSeconds())) {
                        llmGlobalToken = new TokenBudgetView(config.getMinuteMax(), config.getMinuteWindowSeconds(),
                            config.getDayMax(), config.getDayWindowSeconds());
                    }
                }
                case EMBEDDING_UPLOAD_TOKEN -> {
                    if (Objects.nonNull(config.getMinuteMax()) && Objects.nonNull(config.getMinuteWindowSeconds())
                        && Objects.nonNull(config.getDayMax()) && Objects.nonNull(config.getDayWindowSeconds())) {
                        embeddingUploadToken = new TokenBudgetView(config.getMinuteMax(),
                            config.getMinuteWindowSeconds(), config.getDayMax(), config.getDayWindowSeconds());
                    }
                }
                case EMBEDDING_QUERY_REQUEST -> {
                    if (Objects.nonNull(config.getMinuteMax()) && Objects.nonNull(config.getMinuteWindowSeconds())
                        && Objects.nonNull(config.getDayMax()) && Objects.nonNull(config.getDayWindowSeconds())) {
                        embeddingQueryRequest = new DualWindowLimitView(config.getMinuteMax(),
                            config.getMinuteWindowSeconds(), config.getDayMax(), config.getDayWindowSeconds());
                    }
                }
                case EMBEDDING_QUERY_GLOBAL_TOKEN -> {
                    if (Objects.nonNull(config.getMinuteMax()) && Objects.nonNull(config.getMinuteWindowSeconds())
                        && Objects.nonNull(config.getDayMax()) && Objects.nonNull(config.getDayWindowSeconds())) {
                        embeddingQueryGlobalToken = new TokenBudgetView(config.getMinuteMax(),
                            config.getMinuteWindowSeconds(), config.getDayMax(), config.getDayWindowSeconds());
                    }
                }
                default -> {
                    // 忽略未知的配置，以便未来扩展
                }
            }
        }

        return new RateLimitSettingsView(chatMessage, llmGlobalToken, embeddingUploadToken, embeddingQueryRequest,
            embeddingQueryGlobalToken);
    }

    /**
     * 获取当前限流设置
     */
    private RateLimitSettingsView buildDefaultSettings() {
        return new RateLimitSettingsView(
            new WindowLimitView(properties.getChatMessage().getMax(), properties.getChatMessage().getWindowSeconds()),
            new TokenBudgetView(properties.getLlmGlobalToken().getMinuteMax(),
                properties.getLlmGlobalToken().getMinuteWindowSeconds(), properties.getLlmGlobalToken().getDayMax(),
                properties.getLlmGlobalToken().getDayWindowSeconds()),
            new TokenBudgetView(properties.getEmbeddingUploadToken().getMinuteMax(),
                properties.getEmbeddingUploadToken().getMinuteWindowSeconds(),
                properties.getEmbeddingUploadToken().getDayMax(),
                properties.getEmbeddingUploadToken().getDayWindowSeconds()),
            new DualWindowLimitView(properties.getEmbeddingQueryRequest().getMinuteMax(),
                properties.getEmbeddingQueryRequest().getMinuteWindowSeconds(),
                properties.getEmbeddingQueryRequest().getDayMax(),
                properties.getEmbeddingQueryRequest().getDayWindowSeconds()),
            new TokenBudgetView(properties.getEmbeddingQueryGlobalToken().getMinuteMax(),
                properties.getEmbeddingQueryGlobalToken().getMinuteWindowSeconds(),
                properties.getEmbeddingQueryGlobalToken().getDayMax(),
                properties.getEmbeddingQueryGlobalToken().getDayWindowSeconds()));
    }
}
