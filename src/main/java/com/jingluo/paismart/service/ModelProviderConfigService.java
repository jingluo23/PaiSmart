package com.jingluo.paismart.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.domain.response.ModelProviderSettingsView;
import com.jingluo.paismart.domain.response.ProviderConfigView;
import com.jingluo.paismart.domain.response.ScopeSettingsView;
import com.jingluo.paismart.model.ModelProviderConfig;
import com.jingluo.paismart.repository.ModelProviderConfigRepository;

import io.micrometer.common.util.StringUtils;

/**
 * @Author: 鲸落
 * @Date: 2026/9/14 16:27
 * @Desc:
 */
@Service
public class ModelProviderConfigService {

    @Value("${deepseek.api.url:https://api.deepseek.com/v1}")
    private String deepSeekApiUrl;

    @Value("${deepseek.api.key:}")
    private String deepSeekApiKey;

    @Value("${deepseek.api.model:deepseek-chat}")
    private String deepSeekModel;

    @Value("${embedding.api.url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String embeddingApiUrl;

    @Value("${embedding.api.key:}")
    private String embeddingApiKey;

    @Value("${embedding.api.model:text-embedding-v4}")
    private String embeddingModel;

    @Value("${embedding.api.dimension:2048}")
    private Integer embeddingDimension;

    @Autowired
    private SecretCryptoService secretCryptoService;

    @Autowired
    private ModelProviderConfigRepository modelProviderConfigRepository;

    public static final String SCOPE_LLM = "llm";

    public static final String SCOPE_EMBEDDING = "embedding";

    public static final String API_STYLE_OPENAI = "openai-compatible";

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private static final String EMBEDDINGS_PATH = "/embeddings";

    /**
     * 获取当前配置
     *
     * @return
     */
    public ModelProviderSettingsView getCurrentSettings() {
        ModelProviderSettingsView modelProviderSettingsView = buildDefaultSettings();
        return mergeOverrides(modelProviderSettingsView, modelProviderConfigRepository.findAll());
    }

    /**
     * 合并配置
     * 
     * @param defaults
     * @param configs
     * @return
     */
    private ModelProviderSettingsView mergeOverrides(ModelProviderSettingsView defaults,
        List<ModelProviderConfig> configs) {
        ScopeSettingsView llm = mergeScope(defaults.getLlm(), configs);
        ScopeSettingsView embedding = mergeScope(defaults.getEmbedding(), configs);

        return new ModelProviderSettingsView(llm, embedding);
    }

    /**
     * 合并配置
     * 
     * @param defaults
     * @param configs
     * @return
     */
    private ScopeSettingsView mergeScope(ScopeSettingsView defaults, List<ModelProviderConfig> configs) {
        Map<String, ProviderConfigView> merged = toProviderMap(defaults.getProviders());
        String activeProvider = defaults.getActiveProvider();

        for (ModelProviderConfig config : configs) {
            if (!defaults.getScope().equals(config.getConfigScope())) {
                continue;
            }

            ProviderConfigView fallback = merged.get(config.getProviderCode());
            if (Objects.isNull(fallback)) {
                continue;
            }

            String decryptedApiKey = secretCryptoService.decrypt(config.getApiKeyCiphertext());
            merged.put(config.getProviderCode(),
                new ProviderConfigView(config.getProviderCode(), config.getDisplayName(), config.getApiStyle(),
                    normalizeOpenAiCompatibleBaseUrl(config.getApiBaseUrl()), config.getModelName(),
                    Objects.nonNull(config.getEmbeddingDimension()) ? config.getEmbeddingDimension()
                        : fallback.getDimension(),
                    config.isEnabled(), config.isActive(), hasValue(decryptedApiKey),
                    secretCryptoService.mask(decryptedApiKey)));

            if (config.isActive()) {
                activeProvider = config.getProviderCode();
            }
        }

        List<ProviderConfigView> providers = new ArrayList<>(merged.values());
        providers.sort(Comparator.comparing(ProviderConfigView::getProvider));

        return new ScopeSettingsView(defaults.getScope(), activeProvider, providers);
    }

    /**
     * 兼容OpenAI的URL
     *
     * @param rawBaseUrl
     * @return
     */
    public static String normalizeOpenAiCompatibleBaseUrl(String rawBaseUrl) {
        if (StringUtils.isBlank(rawBaseUrl)) {
            return null;
        }

        String normalized = rawBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        boolean changed;
        do {
            changed = false;
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (lower.endsWith(CHAT_COMPLETIONS_PATH)) {
                normalized = normalized.substring(0, normalized.length() - CHAT_COMPLETIONS_PATH.length());
                changed = true;
            } else if (lower.endsWith(EMBEDDINGS_PATH)) {
                normalized = normalized.substring(0, normalized.length() - EMBEDDINGS_PATH.length());
                changed = true;
            }
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
                changed = true;
            }
        } while (changed);

        return normalized;
    }

    /**
     * 转换为Map
     *
     * @param providers
     * @return
     */
    private Map<String, ProviderConfigView> toProviderMap(List<ProviderConfigView> providers) {
        Map<String, ProviderConfigView> result = new LinkedHashMap<>();
        for (ProviderConfigView provider : providers) {
            result.put(provider.getProvider(), provider);
        }

        return result;
    }

    /**
     * 构建默认配置
     */
    private ModelProviderSettingsView buildDefaultSettings() {
        ScopeSettingsView llm = new ScopeSettingsView(SCOPE_LLM, "deepseek",
            List.of(
                new ProviderConfigView("deepseek", "DeepSeek", API_STYLE_OPENAI, deepSeekApiUrl, deepSeekModel, null,
                    true, true, hasValue(deepSeekApiKey), secretCryptoService.mask(deepSeekApiKey)),
                new ProviderConfigView("qwen", "Qwen", API_STYLE_OPENAI,
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash", null, true, false, false, ""),
                new ProviderConfigView("zhipu", "ZhipuAI", API_STYLE_OPENAI, "https://open.bigmodel.cn/api/paas/v4",
                    "glm-4.5-air", null, true, false, false, "")));
        ScopeSettingsView embedding = new ScopeSettingsView(SCOPE_EMBEDDING, "aliyun",
            List.of(new ProviderConfigView("aliyun", "阿里云", API_STYLE_OPENAI, embeddingApiUrl, embeddingModel,
                embeddingDimension, true, true, hasValue(embeddingApiKey), secretCryptoService.mask(embeddingApiKey)),
                new ProviderConfigView("zhipu", "智谱AI", API_STYLE_OPENAI, "https://open.bigmodel.cn/api/paas/v4",
                    "embedding-3", 2048, true, false, false, "")));

        return new ModelProviderSettingsView(llm, embedding);
    }

    /**
     * 构建当前配置
     */
    private boolean hasValue(String value) {
        return StringUtils.isNotBlank(value);
    }
}
