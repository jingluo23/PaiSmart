package com.jingluo.paismart.controller;

import com.jingluo.paismart.domain.request.FeedbackRequest;
import com.jingluo.paismart.domain.response.ResponseResult;
import com.jingluo.paismart.domain.response.ToolExecutionResult;
import com.jingluo.paismart.handler.ChatWebSocketHandler;
import com.jingluo.paismart.service.AgentToolRegistry;
import com.jingluo.paismart.service.ChatGenerationStateService;
import com.jingluo.paismart.utils.JwtUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 聊天模块 REST 控制器
 * <p>
 * 提供 WebSocket 令牌换取、生成任务查询与用户反馈提交接口。
 * 生成任务指一次流式回答（generation），状态与内容存储于 Redis。
 *
 * @Author: 鲸落
 * @Date: 2026/9/20 16:25
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ChatGenerationStateService chatGenerationStateService;

    @Autowired
    private AgentToolRegistry agentToolRegistry;

    /**
     * 换取 WebSocket 内部命令令牌
     * <p>
     * 校验 HTTP 侧的 JWT 后，下发用于在 WebSocket 通道执行停止生成等特权命令的内部令牌。
     *
     * @param token HTTP Authorization 头，格式 "Bearer {jwt}"
     * @return 内部命令令牌
     */
    @GetMapping("/websocket-token")
    public ResponseResult<?> getWebSocketToken(@RequestHeader("Authorization") String token) {
        if (StringUtils.isBlank(token) || !token.startsWith("Bearer ")) {
            return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), "令牌格式无效");
        }

        String jwtToken = token.replace("Bearer ", "");
        if (!jwtUtils.validateToken(jwtToken)) {
            return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), "令牌无效");
        }

        String cmdToken = ChatWebSocketHandler.getInternalCmdToken();

        // 检查token是否有效
        if (StringUtils.isBlank(cmdToken) || cmdToken.trim().isEmpty()) {
            return ResponseResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Token生成失败");
        }

        return ResponseResult.success(cmdToken);
    }

    /**
     * 查询指定生成任务的完整快照（仅限任务归属人）
     *
     * @param generationId 生成任务 ID
     * @param token        HTTP Authorization 头，格式 "Bearer {jwt}"
     * @return 生成任务快照（含回答内容与引用映射），不存在或非本人任务时 data 为 null
     */
    @GetMapping("/generation/{generationId}")
    public ResponseResult<?> getGeneration(@PathVariable String generationId,
        @RequestHeader("Authorization") String token) {
        String userId = extractValidatedUserId(token);
        if (StringUtils.isBlank(userId)) {
            return ResponseResult.fail(HttpStatus.UNAUTHORIZED.value(), "无效令牌");
        }

        return ResponseResult
            .success(chatGenerationStateService.getGenerationForUser(generationId, userId).orElse(null));
    }

    /**
     * 校验 Authorization 头中的 JWT 并提取用户 ID
     *
     * @param authorization HTTP Authorization 头
     * @return 用户 ID；令牌缺失、格式错误或校验失败时返回 null
     */
    private String extractValidatedUserId(String authorization) {
        if (StringUtils.isBlank(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }

        String jwtToken = authorization.replace("Bearer ", "");
        if (!jwtUtils.validateToken(jwtToken)) {
            return null;
        }

        return jwtUtils.extractUserIdFromToken(jwtToken);
    }

    /**
     * 查询当前用户正在进行（或最近一次）的生成任务快照
     * <p>
     * 用于断线重连后恢复界面：若任务仍在流式中可继续等待增量。
     *
     * @param token HTTP Authorization 头，格式 "Bearer {jwt}"
     * @return 活动生成任务快照，无活动任务时 data 为 null
     */
    @GetMapping("/active-generation")
    public ResponseResult<?> getActiveGeneration(@RequestHeader("Authorization") String token) {
        String userId = extractValidatedUserId(token);
        if (StringUtils.isBlank(userId)) {
            return ResponseResult.fail(HttpStatus.UNAUTHORIZED.value(), "无效令牌");
        }

        return ResponseResult.success(chatGenerationStateService.getActiveGenerationForUser(userId).orElse(null));
    }

    /**
     * 提交用户反馈
     * <p>
     * 复用 Agent 工具 submit_feedback 的落库逻辑，反馈以 Hash 形式记录到 Redis。
     *
     * @param token   HTTP Authorization 头，格式 "Bearer {jwt}"
     * @param request 反馈请求体，rating 必填（good/bad）
     * @return 工具执行的结构化结果数据
     */
    @PostMapping("/feedback")
    public ResponseResult<?> submitFeedback(@RequestHeader("Authorization") String token,
        @RequestBody FeedbackRequest request) {
        String userId = extractValidatedUserId(token);
        if (StringUtils.isBlank(userId)) {
            return ResponseResult.fail(HttpStatus.UNAUTHORIZED.value(), "无效令牌");
        }

        if (Objects.isNull(request) || StringUtils.isBlank(request.getRating())) {
            return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), "rating 不能为空");
        }

        Map<String, Object> arguments = new HashMap<>();
        arguments.put("rating", request.getRating());
        String reason = buildFeedbackReason(request);
        if (!reason.isBlank()) {
            arguments.put("reason", reason);
        }

        ToolExecutionResult result = agentToolRegistry.executeTool("submit_feedback", arguments, userId);

        return ResponseResult.success(result.getData());
    }

    /**
     * 拼装反馈原因文本：自由说明 + 会话/生成任务标识，便于反馈溯源
     *
     * @param request 反馈请求体
     * @return 拼装后的原因文本，可能为空串
     */
    private String buildFeedbackReason(FeedbackRequest request) {
        StringBuilder reason = new StringBuilder();
        if (StringUtils.isNotBlank(request.getReason())) {
            reason.append(request.getReason().trim());
        }

        if (StringUtils.isNotBlank(request.getConversationId())) {
            appendReasonPart(reason, "conversationId=" + request.getConversationId().trim());
        }

        if (StringUtils.isNotBlank(request.getGenerationId())) {
            appendReasonPart(reason, "generationId=" + request.getGenerationId().trim());
        }

        return reason.toString();
    }

    /**
     * 追加原因片段，非首段时以 "; " 分隔
     *
     * @param reason 原因文本构建器
     * @param part   待追加的片段
     */
    private void appendReasonPart(StringBuilder reason, String part) {
        if (!reason.isEmpty()) {
            reason.append("; ");
        }

        reason.append(part);
    }
}
