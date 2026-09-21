package com.jingluo.paismart.config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 17:24
 * @Desc: Spring MVC Web 配置：静态资源映射、日志拦截器注册、
 *        以及 UTF-8 字符串转换器和中文不转义的 JSON 转换器
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private LoggingInterceptor loggingInterceptor;

    /**
     * 配置静态资源映射：/static/** 指向 classpath:/static/，
     * 根路径 /** 兜底映射到多个默认静态资源目录
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 配置静态资源处理
        registry.addResourceHandler("/static/**").addResourceLocations("classpath:/static/");

        // 添加根路径的静态资源处理
        registry.addResourceHandler("/**").addResourceLocations("classpath:/static/", "classpath:/public/",
            "classpath:/resources/", "classpath:/META-INF/resources/");
    }

    /**
     * 注册日志拦截器：拦截所有请求，排除静态资源与页面文件，避免无意义日志
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册日志拦截器，排除静态资源
        registry.addInterceptor(loggingInterceptor).addPathPatterns("/**").excludePathPatterns("/static/**", "/css/**",
            "/js/**", "/images/**", "/*.ico", "/*.html");
    }

    /**
     * 配置消息转换器：字符串响应统一使用 UTF-8 编码，
     * JSON 响应关闭非 ASCII 转义，保证中文原样输出
     */
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 配置字符串转换器，使用UTF-8编码
        StringHttpMessageConverter stringConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);
        stringConverter.setWriteAcceptCharset(false); // 避免在响应头中添加charset参数
        converters.add(stringConverter);

        // 配置JSON转换器
        MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter();
        ObjectMapper objectMapper = jsonConverter.getObjectMapper();

        // 确保中文字符不被转义为Unicode编码
        objectMapper.getFactory().configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, false);

        jsonConverter.setObjectMapper(objectMapper);
        converters.add(jsonConverter);
    }
}
