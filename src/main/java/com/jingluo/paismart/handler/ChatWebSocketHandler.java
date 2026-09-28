package com.jingluo.paismart.handler;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingluo.paismart.service.ChatHandler;
import com.jingluo.paismart.service.ChatSessionRegistry;
import com.jingluo.paismart.utils.JwtUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 聊天 WebSocket 处理器
 * <p>
 * 建连时基于 URL 路径中的 JWT 完成鉴权并注册会话；通道上承载三类消息： 心跳保活（ping/pong）、携带内部令牌的停止生成指令、普通聊天文本（交由 ChatHandler 处理）。 客户端需先通过 HTTP
 * 接口换取内部命令令牌，才可在 WebSocket 上执行停止生成等特权命令。
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 16:33
 */
@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ChatHandler chatHandler;

    @Autowired
    private ChatSessionRegistry chatSessionRegistry;

    /**
     * 内部命令令牌：客户端通过 /websocket-token 接口换取后，
     * 用于在 WebSocket 通道上执行停止生成等特权命令
     */
    private static final String INTERNAL_CMD_TOKEN = "WSS_STOP_CMD_" + System.currentTimeMillis() % 1000000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 前端心跳探测消息，收到后原路回 pong，不进入聊天链路
     */
    private static final String HEARTBEAT_PING = "__chat_ping__";

    /**
     * 心跳应答消息
     */
    private static final String HEARTBEAT_PONG = "__chat_pong__";

    /**
     * 获取内部命令令牌
     *
     * @return 内部命令令牌
     */
    public static String getInternalCmdToken() {
        return INTERNAL_CMD_TOKEN;
    }

    /**
     * 连接建立回调：从 URL 路径提取 JWT 并校验，非法连接直接以策略违规关闭； 鉴权通过后把会话注册到 ChatSessionRegistry，并向对端推送 connection 消息
     *
     * @param session
     *            新建立的 WebSocket 会话
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String jwtToken;
        try {
            jwtToken = jwtUtils.extractToken(session);
            if (!jwtUtils.validateToken(jwtToken)) {
                log.warn("拒绝无效WebSocket连接，会话ID: {}", session.getId());
                session.close(CloseStatus.POLICY_VIOLATION);

                return;
            }
        } catch (Exception exception) {
            log.warn("拒绝非法WebSocket连接，会话ID: {}, 原因: {}", session.getId(), exception.getMessage());
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (Exception closeException) {
                log.warn("关闭无效WebSocket连接失败: {}", closeException.getMessage(), closeException);
            }
            return;
        }

        String userId = jwtUtils.extractUserId(jwtToken);
        chatSessionRegistry.registerSession(userId, session);

        // 发送会话ID到前端
        try {
            Map<String, String> connectionMessage =
                Map.of("type", "connection", "sessionId", session.getId(), "message", "WebSocket连接已建立");
            String jsonMessage = objectMapper.writeValueAsString(connectionMessage);
            session.sendMessage(new TextMessage(jsonMessage));
        } catch (Exception e) {
            log.warn("发送会话ID失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 文本消息处理回调：按消息类型路由
     * <p>
     * 处理顺序：心跳探测直接回 pong；以 "{" 开头的 JSON 消息解析为系统指令， 仅当 type=stop 且内部命令令牌匹配时执行停止生成（防止越权停止他人任务）； 其余消息一律作为普通聊天文本交给
     * ChatHandler。
     *
     * @param session
     *            发起消息的 WebSocket 会话
     * @param message
     *            文本消息
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = jwtUtils.extractUserId(jwtUtils.extractToken(session));
        try {
            String payload = message.getPayload();

            // 心跳消息只用于保活连接，不进入聊天处理链路。
            if (HEARTBEAT_PING.equals(payload)) {
                session.sendMessage(new TextMessage(HEARTBEAT_PONG));
                return;
            }

            // 检查是否是JSON格式的系统指令
            if (payload.trim().startsWith("{")) {
                try {
                    Map<String, Object> jsonMessage = objectMapper.readValue(payload, Map.class);
                    String messageType = (String)jsonMessage.get("type");
                    String internalToken = (String)jsonMessage.get("_internal_cmd_token");
                    String generationId = (String)jsonMessage.get("generationId");

                    // 只有包含正确内部令牌的停止指令才处理
                    if ("stop".equals(messageType) && INTERNAL_CMD_TOKEN.equals(internalToken)) {
                        // 处理停止指令
                        chatHandler.stopResponse(userId, generationId);

                        return;
                    }

                    // 其他JSON消息当作普通消息处理
                } catch (Exception jsonParseError) {
                    // JSON解析失败，当作普通文本消息处理
                    log.warn("JSON解析失败，当作普通消息处理: {}", jsonParseError.getMessage());
                }
            }

            // 普通聊天消息处理（保持向下兼容）
            chatHandler.processMessage(userId, payload, session);

        } catch (Exception e) {
            log.warn("处理消息出错，用户ID: {}，会话ID: {}，错误: {}", userId, session.getId(), e.getMessage(), e);

            sendErrorMessage(session, "消息处理失败：" + e.getMessage());
        }
    }

    /**
     * 向会话推送一条 error 类型的 JSON 错误消息，发送失败只记日志
     *
     * @param session
     *            WebSocket 会话
     * @param errorMessage
     *            错误文案
     */
    private void sendErrorMessage(WebSocketSession session, String errorMessage) {
        try {
            Map<String, String> error = Map.of("error", errorMessage);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
        } catch (Exception e) {
            log.error("发送错误消息失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 连接关闭回调：从注册表注销会话（身份解析失败时仍按 unknown 记录日志， 不影响注销的容错处理）
     *
     * @param session
     *            已关闭的 WebSocket 会话
     * @param status
     *            关闭状态
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = "unknown";
        try {
            userId = jwtUtils.extractUserId(jwtUtils.extractToken(session));

            chatSessionRegistry.unregisterSession(userId, session);
        } catch (Exception e) {
            log.warn("关闭连接时无法解析用户信息，会话ID: {}", session.getId());
        }

        if (CloseStatus.POLICY_VIOLATION.equals(status)) {
            log.warn("WebSocket连接因策略校验失败被关闭，用户ID: {}，会话ID: {}，状态: {}", userId, session.getId(), status);
        } else {
            log.warn("WebSocket连接已关闭，用户ID: {}，会话ID: {}，状态: {}", userId, session.getId(), status);
        }

    }
}
