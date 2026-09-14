package com.jingluo.paismart.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.jingluo.paismart.domain.request.ProviderUpsertRequest;
import com.jingluo.paismart.domain.request.UpdateScopeRequest;
import com.jingluo.paismart.domain.response.ModelProviderSettingsView;
import com.jingluo.paismart.domain.response.ProviderConfigView;
import com.jingluo.paismart.domain.response.ScopeSettingsView;
import com.jingluo.paismart.exception.CustomException;
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

    /**
     * 更新作用域
     *
     * @param scope
     * @param request
     * @param updatedBy
     * @return
     */
    public synchronized ScopeSettingsView updateScope(String scope, UpdateScopeRequest request, String updatedBy) {
        String normalizedScope = normalizeScope(scope);
        validateUpdateRequest(normalizedScope, request);

        ScopeSettingsView existingScope = resolveScope(normalizedScope, getCurrentSettings());
        Map<String, ProviderConfigView> existingMap = toProviderMap(existingScope.getProviders());
        String currentActiveProvider = existingScope.getActiveProvider();
        ProviderConfigView currentActiveConfig = existingMap.get(currentActiveProvider);

        if (SCOPE_EMBEDDING.equals(normalizedScope)
            && !Objects.equals(request.getActiveProvider(), currentActiveProvider)) {
            ProviderUpsertRequest target = findProviderRequest(request.getProviders(), request.getActiveProvider());
            if (Objects.nonNull(target) && Objects.nonNull(currentActiveConfig)
                && requiresEmbeddingReindex(currentActiveConfig, target)) {
                throw new CustomException("Embedding 模型切换需要重嵌入任务，当前版本不支持直接切换 active provider", HttpStatus.CONFLICT);
            }
        }

        List<ModelProviderConfig> persistedScopeConfigs =
            modelProviderConfigRepository.findByConfigScopeOrderByProviderCodeAsc(normalizedScope);
        Map<String, ModelProviderConfig> persistedMap = new LinkedHashMap<>();
        for (ModelProviderConfig config : persistedScopeConfigs) {
            persistedMap.put(config.getProviderCode(), config);
        }

        for (ProviderUpsertRequest item : request.getProviders()) {
            String provider = normalizeProvider(item.getProvider());
            ProviderConfigView fallback = existingMap.get(provider);
            if (Objects.isNull(fallback)) {
                throw new CustomException("不支持的 provider: " + provider, HttpStatus.BAD_REQUEST);
            }

            ModelProviderConfig entity = persistedMap.getOrDefault(provider, new ModelProviderConfig());
            entity.setConfigScope(normalizedScope);
            entity.setProviderCode(provider);
            entity.setDisplayName(fallback.getDisplayName());
            entity.setApiStyle(fallback.getApiStyle());
            entity.setApiBaseUrl(normalizeOpenAiCompatibleBaseUrl(
                requireNonBlank(item.getApiBaseUrl(), fallback.getApiBaseUrl(), provider + " API 地址不能为空")));
            entity.setModelName(requireNonBlank(item.getModel(), fallback.getModel(), provider + " 模型不能为空"));
            entity.setEmbeddingDimension(SCOPE_EMBEDDING.equals(normalizedScope)
                ? Optional.ofNullable(item.getDimension()).orElse(fallback.getDimension()) : null);
            entity.setEnabled(item.getEnabled() == null ? fallback.isEnabled() : item.getEnabled());
            entity.setActive(provider.equals(request.getActiveProvider()));
            entity.setUpdatedBy(updatedBy);
            entity.setApiKeyCiphertext(resolveCiphertext(item.getApiKey(), fallback));
            modelProviderConfigRepository.save(entity);
            persistedMap.put(provider, entity);
        }

        for (ModelProviderConfig entity : persistedMap.values()) {
            boolean shouldBeActive = entity.getProviderCode().equals(request.getActiveProvider());
            if (entity.isActive() != shouldBeActive) {
                entity.setActive(shouldBeActive);
                entity.setUpdatedBy(updatedBy);
                modelProviderConfigRepository.save(entity);
            }
        }

        ModelProviderSettingsView currentSettings = getCurrentSettings();

        return resolveScope(normalizedScope, currentSettings);
    }

    /**
     * 解析密钥
     * 
     * @param rawApiKey
     * @param fallback
     * @return
     */
    private String resolveCiphertext(String rawApiKey, ProviderConfigView fallback) {
        if (StringUtils.isNotBlank(rawApiKey)) {
            return secretCryptoService.encrypt(rawApiKey.trim());
        }

        if (!fallback.isHasApiKey()) {
            return null;
        }

        Optional<ModelProviderConfig> persisted = modelProviderConfigRepository
            .findByConfigScopeAndProviderCode(resolveScopeByProvider(fallback.getProvider()), fallback.getProvider());
        return persisted.map(ModelProviderConfig::getApiKeyCiphertext).orElseGet(() -> {
            if ("deepseek".equals(fallback.getProvider())) {
                return secretCryptoService.encrypt(deepSeekApiKey);
            }

            if ("aliyun".equals(fallback.getProvider())) {
                return secretCryptoService.encrypt(embeddingApiKey);
            }

            return null;
        });
    }

    /**
     * 根据 provider 解析作用域
     *
     * @param provider
     * @return
     */
    private String resolveScopeByProvider(String provider) {
        ModelProviderSettingsView currentSettings = getCurrentSettings();
        ScopeSettingsView llmScope = currentSettings.getLlm();
        if (llmScope.getProviders().stream().filter(Objects::nonNull)
            .anyMatch(item -> item.getProvider().equals(provider))) {
            return SCOPE_LLM;
        }

        return SCOPE_EMBEDDING;
    }

    /**
     * 非空校验
     *
     * @param candidate
     * @param fallback
     * @param message
     * @return
     */
    private String requireNonBlank(String candidate, String fallback, String message) {
        String value = StringUtils.isNotBlank(candidate) ? candidate.trim() : fallback;
        if (StringUtils.isBlank(value)) {
            throw new CustomException(message, HttpStatus.BAD_REQUEST);
        }

        return value;
    }

    /**
     * 是否需要重嵌入
     *
     * @param current
     * @param target
     * @return
     */
    private boolean requiresEmbeddingReindex(ProviderConfigView current, ProviderUpsertRequest target) {
        if (!Objects.equals(current.getProvider(), normalizeProvider(target.getProvider()))) {
            return Boolean.TRUE;
        }

        if (!Objects.equals(current.getModel(), target.getModel())) {
            return Boolean.TRUE;
        }

        return !Objects.equals(current.getDimension(), target.getDimension());
    }

    /**
     * 根据 provider 查找 ProviderUpsertRequest
     *
     * @param providers
     * @param provider
     * @return
     */
    private ProviderUpsertRequest findProviderRequest(List<ProviderUpsertRequest> providers, String provider) {
        return providers.stream().filter(Objects::nonNull)
            .filter(item -> normalizeProvider(item.getProvider()).equals(provider)).findFirst().orElse(null);
    }

    /**
     * 根据作用域解析配置
     *
     * @param scope
     * @param settings
     * @return
     */
    private ScopeSettingsView resolveScope(String scope, ModelProviderSettingsView settings) {
        String normalizedScope = normalizeScope(scope);

        return SCOPE_LLM.equals(normalizedScope) ? settings.getLlm() : settings.getEmbedding();
    }

    /**
     * 验证更新请求
     *
     * @param scope
     * @param request
     */
    private void validateUpdateRequest(String scope, UpdateScopeRequest request) {
        if (Objects.isNull(request) || CollectionUtils.isEmpty(request.getProviders())) {
            throw new CustomException("模型配置不能为空", HttpStatus.BAD_REQUEST);
        }

        String activeProvider = normalizeProvider(request.getActiveProvider());

        boolean activeExists = false;
        boolean activeEnabled = false;
        for (ProviderUpsertRequest provider : request.getProviders()) {
            String providerCode = normalizeProvider(provider.getProvider());
            if (providerCode.equals(activeProvider)) {
                activeExists = true;
                activeEnabled = Objects.isNull(provider.getEnabled()) || provider.getEnabled();
            }

            if (StringUtils.isBlank(provider.getApiBaseUrl())) {
                throw new CustomException(providerCode + " API 地址不能为空", HttpStatus.BAD_REQUEST);
            }

            if (StringUtils.isBlank(provider.getModel())) {
                throw new CustomException(providerCode + " 模型不能为空", HttpStatus.BAD_REQUEST);
            }

            if (SCOPE_EMBEDDING.equals(scope) && Objects.nonNull(provider.getDimension())
                && provider.getDimension() <= 0) {
                throw new CustomException(providerCode + " Embedding 维度必须大于 0", HttpStatus.BAD_REQUEST);
            }
        }

        if (!activeExists) {
            throw new CustomException("激活 provider 不在配置列表中", HttpStatus.BAD_REQUEST);
        }

        if (!activeEnabled) {
            throw new CustomException("激活 provider 必须处于启用状态", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 规范化 provider
     *
     * @param provider
     * @return
     */
    private String normalizeProvider(String provider) {
        if (StringUtils.isBlank(provider)) {
            throw new CustomException("provider 不能为空", HttpStatus.BAD_REQUEST);
        }

        return provider.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化作用域
     *
     * @param scope
     * @return
     */
    private String normalizeScope(String scope) {
        String normalized = StringUtils.isBlank(scope) ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (!SCOPE_LLM.equals(normalized) && !SCOPE_EMBEDDING.equals(normalized)) {
            throw new CustomException("不支持的模型作用域: " + scope, HttpStatus.BAD_REQUEST);
        }

        return normalized;
    }
}
