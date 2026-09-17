package com.jingluo.paismart.controller;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jingluo.paismart.domain.request.CreateRechargeOrderRequest;
import com.jingluo.paismart.domain.response.PrePayInfoResBo;
import com.jingluo.paismart.domain.response.ResponseResult;
import com.jingluo.paismart.enums.OrderStatus;
import com.jingluo.paismart.model.RechargeOrder;
import com.jingluo.paismart.model.RechargePackage;
import com.jingluo.paismart.service.RechargeService;
import com.jingluo.paismart.utils.JwtUtils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 充值模块接口，提供套餐查询、下单、支付回调、订单查询等能力
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 9:15
 */
@Slf4j
@RestController
@RequestMapping(path = "/api/v1/recharge")
public class RechargeController {

    @Autowired
    private RechargeService rechargeService;

    @Autowired
    private JwtUtils jwtUtils;

    /**
     * 查询可购买的充值套餐列表
     */
    @GetMapping("/packages")
    public ResponseResult getPackages() {
        List<RechargePackage> packages = rechargeService.getAllPackages();

        return ResponseResult.success(packages);
    }

    /**
     * 创建充值订单并返回微信预支付信息（支持套餐充值和自定义金额充值）
     */
    @PostMapping("/create-order")
    public ResponseResult createRechargeOrder(@RequestHeader("Authorization") String token,
        @RequestBody CreateRechargeOrderRequest request) {
        // 从 token 中提取用户 ID
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        if (StringUtils.isBlank(userId)) {
            return ResponseResult.fail(HttpStatus.UNAUTHORIZED.value(), "无效的用户 token");
        }

        // 创建充值订单
        PrePayInfoResBo payInfo =
            rechargeService.createRechargeOrder(userId, request.getPackageId(), request.getCustomAmount());

        return ResponseResult.success(payInfo);
    }

    /**
     * 微信支付结果通知回调，验签后更新订单状态并发放 token
     */
    @PostMapping("/pay-callback")
    public ResponseResult payCallback(HttpServletRequest request) {
        rechargeService.handlePayCallback(request);

        // 返回成功响应给微信
        return ResponseResult.success("");
    }

    /**
     * 分页查询当前用户的充值订单列表，可按订单状态过滤
     */
    @GetMapping("/orders")
    public ResponseResult getUserOrders(@RequestHeader("Authorization") String token,
        @RequestParam(required = false) String status) {
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        if (StringUtils.isBlank(userId)) {
            return ResponseResult.fail(HttpStatus.UNAUTHORIZED.value(), "无效的用户 token");
        }

        OrderStatus orderStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                orderStatus = OrderStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseResult.fail(HttpStatus.BAD_REQUEST.value(), "无效的订单状态");
            }
        }

        List<RechargeOrder> orders = rechargeService.getUserOrders(userId, orderStatus);

        return ResponseResult.success(orders);
    }

    /**
     * 查询订单详情，主动向微信核实最新支付状态
     */
    @GetMapping("/orders/{tradeNo}")
    public ResponseResult getOrderDetail(@RequestHeader("Authorization") String token, @PathVariable String tradeNo) {
        String userId = jwtUtils.extractUserIdFromToken(token.replace("Bearer ", ""));
        if (StringUtils.isBlank(userId)) {
            return ResponseResult.fail(HttpStatus.UNAUTHORIZED.value(), "无效的用户 token");
        }

        // 检查订单支付状态
        RechargeOrder order = rechargeService.checkOrderPayStatus(tradeNo);

        // 验证订单是否属于当前用户
        if (!order.getUserId().equals(userId)) {
            return ResponseResult.fail(HttpStatus.FORBIDDEN.value(), "无权查看该订单");
        }

        return ResponseResult.success(order);
    }
}
