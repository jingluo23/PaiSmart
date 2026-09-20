package com.jingluo.paismart.service;

import java.time.Duration;
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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.jingluo.paismart.domain.request.ProviderConnectionTestRequest;
import com.jingluo.paismart.domain.request.ProviderUpsertRequest;
import com.jingluo.paismart.domain.request.UpdateScopeRequest;
import com.jingluo.paismart.domain.response.ActiveProviderView;
import com.jingluo.paismart.domain.response.ConnectivityTestView;
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

    @Autowired
    private SecretCryptoService secretCryptoService;

    @Autowired
    private ModelProviderConfigRepository modelProviderConfigRepository;

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

    public static final String SCOPE_LLM = "llm";

    public static final String SCOPE_EMBEDDING = "embedding";

    public static final String API_STYLE_OPENAI = "openai-compatible";

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private static final String EMBEDDINGS_PATH = "/embeddings";

    private volatile ModelProviderSettingsView currentSettings;

    /**
     * 构造默认配置
     *
     * @return
     */
    public ModelProviderConfigService() {
        this.currentSettings = buildDefaultSettings();
    }

    /**
     * 获取当前配置
     *
     * @return
     */
    public ModelProviderSettingsView getCurrentSettings() {
        return currentSettings;
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
    public String normalizeOpenAiCompatibleBaseUrl(String rawBaseUrl) {
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

        ScopeSettingsView existingScope = resolveScope(normalizedScope, currentSettings);
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

        reloadSettings();

        return resolveScope(normalizedScope, currentSettings);
    }

    /**
     * 重新加载设置
     */
    public synchronized void reloadSettings() {
        this.currentSettings = mergeOverrides(buildDefaultSettings(), modelProviderConfigRepository.findAll());
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

    /**
     * 测试指定作用域下模型提供者的连通性：LLM 调用 /chat/completions，Embedding 调用 /embeddings， 发送最小化 ping 请求并记录耗时；成功与失败均以结果视图返回，不向上抛出异常
     *
     * @param scope
     *            模型作用域（llm / embedding）
     * @param request
     *            连接测试参数（API 地址、模型、密钥等）
     * @return 连通性测试结果（是否成功、结果描述、耗时毫秒数）
     */
    public ConnectivityTestView testConnection(String scope, ProviderConnectionTestRequest request) {
        String normalizedScope = normalizeScope(scope);
        validateConnectionTestRequest(normalizedScope, request);

        long startAt = System.currentTimeMillis();
        String provider = normalizeOptionalProvider(request.getProvider());
        try {
            WebClient.Builder builder =
                WebClient.builder().baseUrl(normalizeOpenAiCompatibleBaseUrl(request.getApiBaseUrl()))
                    .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);

            String apiKey = resolveConnectionTestApiKey(normalizedScope, provider, request.getApiKey());
            if (StringUtils.isNotBlank(apiKey)) {
                builder.defaultHeader("Authorization", "Bearer " + apiKey);
            }

            WebClient client = builder.build();
            if (SCOPE_LLM.equals(normalizedScope)) {
                Map<String, Object> payload = Map.of("model", request.getModel(), "messages",
                    List.of(Map.of("role", "user", "content", "ping")), "stream", false, "max_tokens", 1);
                client.post().uri("/chat/completions").bodyValue(payload).retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(8));
            } else {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("model", request.getModel());
                payload.put("input", List.of("ping"));
                payload.put("encoding_format", "float");

                if (Objects.nonNull(request.getDimension())) {
                    payload.put("dimension", request.getDimension());
                }

                client.post().uri("/embeddings").bodyValue(payload).retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(8));
            }

            return new ConnectivityTestView(true, "连接成功", System.currentTimeMillis() - startAt);
        } catch (Exception exception) {
            return new ConnectivityTestView(false, formatConnectionFailure(provider, exception),
                System.currentTimeMillis() - startAt);
        }
    }

    /**
     * 将连接测试过程中的异常转换为面向管理端的友好失败提示， 按 HTTP 状态码区分密钥无效（401/403）、地址不可用（404）、参数错误（400）等情况
     *
     * @param provider
     *            提供者编码，用于匹配展示名称
     * @param exception
     *            连接测试抛出的异常
     * @return 可读的失败原因描述
     */
    private String formatConnectionFailure(String provider, Exception exception) {
        WebClientResponseException responseException = findResponseException(exception);
        if (Objects.isNull(responseException)) {
            return exception.getMessage();
        }

        int statusCode = responseException.getStatusCode().value();
        String displayName = StringUtils.isNotBlank(provider) ? resolveProviderDisplayName(provider) : "模型";

        if (statusCode == HttpStatus.UNAUTHORIZED.value() || statusCode == HttpStatus.FORBIDDEN.value()) {
            return displayName + " API Key 无效或未填写，请检查已保存密钥，或在“新 API Key”中重新输入后再测试";
        }

        if (statusCode == HttpStatus.NOT_FOUND.value()) {
            return displayName + " API 地址或接口路径不可用，请检查 Base URL 是否填写到 OpenAI 兼容根地址";
        }

        if (statusCode == HttpStatus.BAD_REQUEST.value()) {
            return displayName + " 请求参数无效，请检查模型标识是否为服务商支持的 API 模型名";
        }

        return responseException.getMessage();
    }

    /**
     * 解析 provider 的展示名称：先在 llm 作用域查找，再在 embedding 作用域查找，均未命中时返回原始编码
     *
     * @param provider
     *            提供者编码
     * @return 展示名称
     */
    private String resolveProviderDisplayName(String provider) {
        return currentSettings.getLlm().getProviders().stream().filter(item -> item.getProvider().equals(provider))
            .findFirst()
            .or(() -> currentSettings.getEmbedding().getProviders().stream()
                .filter(item -> item.getProvider().equals(provider)).findFirst())
            .map(ProviderConfigView::getDisplayName).orElse(provider);
    }

    /**
     * 沿异常调用链向下查找首个 WebClientResponseException（即服务端返回了 HTTP 响应的异常）， 用于判断失败是否由远端接口响应导致
     *
     * @param throwable
     *            待排查的异常
     * @return 找到的响应异常，不存在时返回 null
     */
    private WebClientResponseException findResponseException(Throwable throwable) {
        Throwable current = throwable;
        while (Objects.nonNull(current)) {
            if (current instanceof WebClientResponseException responseException) {
                return responseException;
            }

            current = current.getCause();
        }

        return null;
    }

    /**
     * 解析连接测试使用的 API 密钥：优先取请求中明文传入的密钥；未传时若已保存配置存在密文则解密使用， 否则回退到内置默认密钥（deepseek / aliyun）
     *
     * @param scope
     *            模型作用域（llm / embedding）
     * @param provider
     *            提供者编码，可为 null
     * @param rawApiKey
     *            请求中明文传入的密钥，可为空
     * @return 可用于请求头 Authorization 的密钥，无法解析时返回 null
     */
    private String resolveConnectionTestApiKey(String scope, String provider, String rawApiKey) {
        if (StringUtils.isNotBlank(rawApiKey)) {
            return rawApiKey.trim();
        }

        if (StringUtils.isBlank(provider)) {
            return null;
        }

        ProviderConfigView config = resolveProvider(scope, provider, currentSettings);
        if (!config.isHasApiKey()) {
            return null;
        }

        Optional<ModelProviderConfig> persisted =
            modelProviderConfigRepository.findByConfigScopeAndProviderCode(scope, provider);
        if (persisted.isPresent()) {
            return secretCryptoService.decrypt(persisted.get().getApiKeyCiphertext());
        }

        if ("deepseek".equals(provider)) {
            return deepSeekApiKey;
        }

        if ("aliyun".equals(provider)) {
            return embeddingApiKey;
        }

        return null;
    }

    /**
     * 规范化可选的 provider，为空时返回 null 而不抛出异常
     *
     * @param provider
     *            提供者编码
     * @return 规范化结果，入参为空时返回 null
     */
    private String normalizeOptionalProvider(String provider) {
        return StringUtils.isBlank(provider) ? null : normalizeProvider(provider);
    }

    /**
     * 校验连接测试请求参数的完整性与合法性：请求体、API 地址、模型不能为空， Embedding 维度必须大于 0，已填写的 provider 必须存在于当前配置中
     *
     * @param scope
     *            模型作用域（llm / embedding）
     * @param request
     *            连接测试参数
     */
    private void validateConnectionTestRequest(String scope, ProviderConnectionTestRequest request) {
        if (Objects.isNull(request)) {
            throw new CustomException("连接测试参数不能为空", HttpStatus.BAD_REQUEST);
        }

        if (StringUtils.isBlank(request.getApiBaseUrl())) {
            throw new CustomException("API 地址不能为空", HttpStatus.BAD_REQUEST);
        }

        if (StringUtils.isBlank(request.getModel())) {
            throw new CustomException("模型不能为空", HttpStatus.BAD_REQUEST);
        }

        if (SCOPE_EMBEDDING.equals(scope) && Objects.nonNull(request.getDimension()) && request.getDimension() <= 0) {
            throw new CustomException("Embedding 维度必须大于 0", HttpStatus.BAD_REQUEST);
        }

        if (StringUtils.isNotBlank(request.getProvider())) {
            resolveProvider(scope, normalizeProvider(request.getProvider()), currentSettings);
        }
    }

    /**
     * 从指定配置中查找作用域下对应的提供者配置，未命中时抛出参数异常
     *
     * @param scope
     *            模型作用域（llm / embedding）
     * @param provider
     *            提供者编码
     * @param settings
     *            待查找的模型提供者配置
     * @return 匹配的提供者配置视图
     */
    private ProviderConfigView resolveProvider(String scope, String provider, ModelProviderSettingsView settings) {
        return resolveScope(scope, settings).getProviders().stream().filter(item -> item.getProvider().equals(provider))
            .findFirst().orElseThrow(() -> new CustomException("不支持的 provider: " + provider, HttpStatus.BAD_REQUEST));
    }

    /**
     * 获取指定作用域当前激活的模型提供者：取第一个标记激活的配置并解出可用密钥， 未配置时抛出服务端异常以提示管理员补全配置
     *
     * @param scope
     *            模型作用域（llm / embedding）
     * @return 可直接用于调用的激活提供者视图
     */
    public ActiveProviderView getActiveProvider(String scope) {
        ScopeSettingsView settings = resolveScope(scope, currentSettings);

        return settings.getProviders().stream().filter(ProviderConfigView::isActive).findFirst()
            .map(this::toActiveProvider)
            .orElseThrow(() -> new CustomException("未找到激活的模型配置: " + scope, HttpStatus.INTERNAL_SERVER_ERROR));
    }

    /**
     * 将提供者配置视图转换为激活视图：密钥优先解密数据库持久化密文， 未持久化时回退到内置环境变量密钥，保证旧配置平滑过渡
     *
     * @param provider
     *            提供者配置视图
     * @return 携带可用密钥的激活提供者视图
     */
    private ActiveProviderView toActiveProvider(ProviderConfigView provider) {
        String apiKey = null;
        Optional<ModelProviderConfig> persisted = modelProviderConfigRepository
            .findByConfigScopeAndProviderCode(resolveScopeByProvider(provider.getProvider()), provider.getProvider());
        if (persisted.isPresent()) {
            apiKey = secretCryptoService.decrypt(persisted.get().getApiKeyCiphertext());
        } else if ("deepseek".equals(provider.getProvider())) {
            apiKey = deepSeekApiKey;
        } else if ("aliyun".equals(provider.getProvider())) {
            apiKey = embeddingApiKey;
        }

        return new ActiveProviderView(provider.getProvider(), provider.getDisplayName(), provider.getApiStyle(),
            normalizeOpenAiCompatibleBaseUrl(provider.getApiBaseUrl()), provider.getModel(), apiKey,
            provider.getDimension());
    }
}
