package com.jingluo.paismart.handler;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 聊天 WebSocket 处理器（开发中）
 * <p>
 * 预留流式对话的 WebSocket 通道；当前先提供内部停止命令令牌，
 * 客户端需先通过 HTTP 接口换取该令牌，才可在 WebSocket 上发送停止生成等内部命令。
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 16:33
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    /**
     * 内部命令令牌：客户端通过 /websocket-token 接口换取后，
     * 用于在 WebSocket 通道上执行停止生成等特权命令
     */
    private static final String INTERNAL_CMD_TOKEN = "WSS_STOP_CMD_" + System.currentTimeMillis() % 1000000;

    /**
     * 获取内部命令令牌
     *
     * @return 内部命令令牌
     */
    public static String getInternalCmdToken() {
        return INTERNAL_CMD_TOKEN;
    }
}
