package com.jingluo.paismart.service;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jingluo.paismart.config.RateLimitProperties;
import com.jingluo.paismart.exception.CustomException;
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
     * 更新限流配置：覆盖 rate_limit_configs 表中的对应配置项； 限流执行侧每次检查都通过 getCurrentSettings 动态读取，保存后立即生效，无需重启
     *
     * @param request
     *            五类限流配置的完整设置
     * @param updatedBy
     *            操作人（管理员用户名）
     * @return 保存后的最新设置
     */
    @Transactional(rollbackFor = Exception.class)
    public RateLimitSettingsView updateSettings(RateLimitSettingsView request, String updatedBy) {
        if (Objects.isNull(request) || Objects.isNull(request.getChatMessage()) || Objects.isNull(request.getLlmGlobalToken())
            || Objects.isNull(request.getEmbeddingUploadToken()) || Objects.isNull(request.getEmbeddingQueryRequest())
            || Objects.isNull(request.getEmbeddingQueryGlobalToken())) {
            throw new CustomException("限流配置不完整", HttpStatus.BAD_REQUEST);
        }

        validatePositive(request.getChatMessage().getMax(), request.getChatMessage().getWindowSeconds());
        validatePositive(request.getLlmGlobalToken().getMinuteMax(), request.getLlmGlobalToken().getMinuteWindowSeconds(),
            request.getLlmGlobalToken().getDayMax(), request.getLlmGlobalToken().getDayWindowSeconds());
        validatePositive(request.getEmbeddingUploadToken().getMinuteMax(),
            request.getEmbeddingUploadToken().getMinuteWindowSeconds(), request.getEmbeddingUploadToken().getDayMax(),
            request.getEmbeddingUploadToken().getDayWindowSeconds());
        validatePositive(request.getEmbeddingQueryRequest().getMinuteMax(),
            request.getEmbeddingQueryRequest().getMinuteWindowSeconds(), request.getEmbeddingQueryRequest().getDayMax(),
            request.getEmbeddingQueryRequest().getDayWindowSeconds());
        validatePositive(request.getEmbeddingQueryGlobalToken().getMinuteMax(),
            request.getEmbeddingQueryGlobalToken().getMinuteWindowSeconds(), request.getEmbeddingQueryGlobalToken().getDayMax(),
            request.getEmbeddingQueryGlobalToken().getDayWindowSeconds());

        upsertChatMessage(request.getChatMessage(), updatedBy);
        upsertTokenBudget(LLM_GLOBAL_TOKEN, request.getLlmGlobalToken(), updatedBy);
        upsertTokenBudget(EMBEDDING_UPLOAD_TOKEN, request.getEmbeddingUploadToken(), updatedBy);
        upsertTokenBudget(EMBEDDING_QUERY_REQUEST, new TokenBudgetView(request.getEmbeddingQueryRequest().getMinuteMax(),
            request.getEmbeddingQueryRequest().getMinuteWindowSeconds(), request.getEmbeddingQueryRequest().getDayMax(),
            request.getEmbeddingQueryRequest().getDayWindowSeconds()), updatedBy);
        upsertTokenBudget(EMBEDDING_QUERY_GLOBAL_TOKEN, request.getEmbeddingQueryGlobalToken(), updatedBy);

        return getCurrentSettings();
    }

    /**
     * 参数校验：所有限流值必须为正数
     */
    private void validatePositive(long... values) {
        for (long value : values) {
            if (value <= 0) {
                throw new CustomException("限流配置值必须为正数", HttpStatus.BAD_REQUEST);
            }
        }
    }

    /**
     * 保存聊天消息单窗口限制
     */
    private void upsertChatMessage(WindowLimitView limit, String updatedBy) {
        RateLimitConfig config = rateLimitConfigRepository.findById(CHAT_MESSAGE).orElseGet(RateLimitConfig::new);
        config.setConfigKey(CHAT_MESSAGE);
        config.setSingleMax(limit.getMax());
        config.setSingleWindowSeconds(limit.getWindowSeconds());
        config.setUpdatedBy(updatedBy);
        rateLimitConfigRepository.save(config);
    }

    /**
     * 保存 Token 预算类限制（双窗口），DualWindowLimitView 与 TokenBudgetView 字段同构
     */
    private void upsertTokenBudget(String key, TokenBudgetView limit, String updatedBy) {
        RateLimitConfig config = rateLimitConfigRepository.findById(key).orElseGet(RateLimitConfig::new);
        config.setConfigKey(key);
        config.setMinuteMax(limit.getMinuteMax());
        config.setMinuteWindowSeconds(limit.getMinuteWindowSeconds());
        config.setDayMax(limit.getDayMax());
        config.setDayWindowSeconds(limit.getDayWindowSeconds());
        config.setUpdatedBy(updatedBy);
        rateLimitConfigRepository.save(config);
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
