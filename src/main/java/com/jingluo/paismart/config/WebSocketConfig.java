package com.jingluo.paismart.config;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.jingluo.paismart.handler.ChatWebSocketHandler;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 17:25
 * @Desc: WebSocket 配置：注册聊天处理器到 /chat/{token} 端点，
 *        并按安全配置限制允许的跨域来源
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private ChatWebSocketHandler chatWebSocketHandler;

    /**
     * 允许的跨域来源列表（逗号分隔），与 HTTP 接口共用同一配置项
     */
    @Value("${security.allowed-origins:http://localhost:8080}")
    private String allowedOrigins;

    /**
     * 注册 WebSocket 处理器：聊天连接端点为 /chat/{token}（token 在连接时随路径传入），
     * 来源列表按逗号拆分并过滤空项后作为允许的 Origin 模式
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins =
            Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);

        registry.addHandler(chatWebSocketHandler, "/chat/{token}").setAllowedOriginPatterns(origins);
    }
}
