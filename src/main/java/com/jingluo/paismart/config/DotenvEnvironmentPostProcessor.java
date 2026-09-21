package com.jingluo.paismart.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 16:05
 * @Desc: .env 文件环境后置处理器，在 Spring 环境准备阶段加载工作目录下的 .env 文件，
 *        将其中的键值对注入为属性源（优先级低于系统环境变量），方便本地开发免配置启动
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /**
     * 注入到 Spring 环境中的属性源名称
     */
    private static final String PROPERTY_SOURCE_NAME = "paismartDotenv";

    /**
     * 待加载的 .env 文件名（位于应用工作目录下）
     */
    private static final String DOTENV_FILE = ".env";

    /**
     * 加载 .env 文件并注入属性源；同时应用其中声明的 SPRING_PROFILES_ACTIVE
     *
     * @param environment 应用环境
     * @param application 当前 SpringApplication
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path dotenvPath = Path.of(System.getProperty("user.dir"), DOTENV_FILE);
        if (!Files.isRegularFile(dotenvPath)) {
            return;
        }

        Map<String, Object> properties = loadDotenv(dotenvPath);
        if (properties.isEmpty()) {
            return;
        }

        applyActiveProfiles(environment, properties);

        SystemEnvironmentPropertySource propertySource =
            new SystemEnvironmentPropertySource(PROPERTY_SOURCE_NAME, properties);
        MutablePropertySources propertySources = environment.getPropertySources();
        // 幂等处理：若已存在同名属性源（如上下文刷新重入），先移除旧的
        if (propertySources.contains(PROPERTY_SOURCE_NAME)) {
            propertySources.remove(PROPERTY_SOURCE_NAME);
        }

        // 挂在系统环境变量之后，保证真实环境变量优先于 .env 中的同名配置
        if (propertySources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            propertySources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, propertySource);
            return;
        }

        propertySources.addLast(propertySource);
    }

    /**
     * 将 .env 中声明的 SPRING_PROFILES_ACTIVE 应用为激活 profile（逗号分隔，支持空格）
     *
     * @param environment 应用环境
     * @param properties  .env 解析出的键值对
     */
    private void applyActiveProfiles(ConfigurableEnvironment environment, Map<String, Object> properties) {
        Object rawProfiles = properties.get("SPRING_PROFILES_ACTIVE");
        if (!(rawProfiles instanceof String profilesValue) || profilesValue.isBlank()) {
            return;
        }

        String[] profiles = profilesValue.split(",");
        for (int i = 0; i < profiles.length; i++) {
            profiles[i] = profiles[i].trim();
        }
        environment.setActiveProfiles(profiles);
    }

    /**
     * 逐行解析 .env 文件：跳过空行与 # 注释行，按第一个 = 切分键值，值支持成对引号包裹
     *
     * @param dotenvPath .env 文件路径
     * @return 解析出的键值对（保持声明顺序），文件不可读时返回空 Map
     */
    private Map<String, Object> loadDotenv(Path dotenvPath) {
        Map<String, Object> properties = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(dotenvPath, StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                String line = rawLine.trim();
                // 跳过空行和注释行
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                int separatorIndex = line.indexOf('=');
                if (separatorIndex <= 0) {
                    continue;// 无 = 或 = 前无键名的行直接忽略
                }

                String key = line.substring(0, separatorIndex).trim();
                String value = line.substring(separatorIndex + 1).trim();
                if (key.isEmpty()) {
                    continue;
                }

                properties.put(key, unquote(value));
            }
        } catch (IOException ignored) {
            // Ignore malformed or unreadable .env files and continue with normal environment resolution.
        }
        return properties;
    }

    /**
     * 去除值两端成对的英文引号（单引号或双引号）
     *
     * @param value 原始值
     * @return 去引号后的值
     */
    private String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }

        return value;
    }

    /**
     * 以最高优先级执行，保证 .env 在其他后置处理器读取环境前已生效
     *
     * @return 最高优先级
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
