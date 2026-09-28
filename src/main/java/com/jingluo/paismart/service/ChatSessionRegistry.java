package com.jingluo.paismart.service;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/28 14:33
 * @Desc: 聊天 WebSocket 会话注册表：维护 userId -> WebSocketSession 的在线映射， 为生成任务的流式推送提供统一的 JSON 消息出口
 */
@Slf4j
@Component
public class ChatSessionRegistry {

    @Autowired
    private ObjectMapper objectMapper;

    /** 在线用户会话映射：同一用户仅保留最新连接（后连覆盖先连） */
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * 注册用户会话：同一用户重复连接时新会话覆盖旧会话
     *
     * @param userId
     *            用户 ID
     * @param session
     *            WebSocket 会话
     */
    public void registerSession(String userId, WebSocketSession session) {
        sessions.put(userId, session);
    }

    /**
     * 向指定用户推送 JSON 消息：会话不在线时跳过并记日志； 发送以 session 为锁同步，避免多线程并发写破坏 WebSocket 帧协议； 发送异常只记日志不向上抛出，防止影响生成主流程
     *
     * @param userId
     *            用户 ID
     * @param payload
     *            消息内容（将被序列化为 JSON）
     */
    public void sendJsonToUser(String userId, Map<String, ?> payload) {
        WebSocketSession session = sessions.get(userId);
        if (Objects.isNull(session) || !session.isOpen()) {
            log.warn("用户 {} 当前没有可用的 WebSocket 会话，跳过发送", userId);

            return;
        }

        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
                }
            }
        } catch (Exception e) {
            log.warn("向用户 {} 发送 WebSocket 消息失败: {}", userId, e.getMessage(), e);
        }
    }

    /**
     * 注销用户会话：仅当当前映射的就是该会话时才移除， 防止把后来顶替登录的新会话误删
     *
     * @param userId
     *            用户 ID
     * @param session
     *            待注销的 WebSocket 会话
     */
    public void unregisterSession(String userId, WebSocketSession session) {
        sessions.computeIfPresent(userId, (key, current) -> current == session ? null : current);
    }
}
