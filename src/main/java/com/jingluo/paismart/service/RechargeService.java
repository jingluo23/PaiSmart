package com.jingluo.paismart.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jingluo.paismart.domain.response.PayCallbackBo;
import com.jingluo.paismart.domain.response.PayOrderReq;
import com.jingluo.paismart.domain.response.PrePayInfoResBo;
import com.jingluo.paismart.enums.OrderStatus;
import com.jingluo.paismart.enums.RechargeStatusEnum;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.RechargeOrder;
import com.jingluo.paismart.model.RechargePackage;
import com.jingluo.paismart.repository.RechargeOrderRepository;
import com.jingluo.paismart.repository.RechargePackageRepository;
import com.jingluo.paismart.utils.PriceUtil;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 充值业务服务，负责套餐查询、订单创建、支付回调处理与订单查询
 *
 * @Author: 鲸落
 * @Date: 2026/9/17 9:22
 */
@Slf4j
@Service
public class RechargeService {

    @Autowired
    private RechargePackageRepository rechargePackageRepository;

    @Autowired
    private WxPayService wxPayService;

    @Autowired
    private RechargeOrderRepository rechargeOrderRepository;

    @Autowired
    private UserTokenService userTokenService;

    /**
     * 查询所有可购买的充值套餐（已启用、未删除且价格大于 0）
     *
     * @return 套餐列表，按排序字段升序
     */
    public List<RechargePackage> getAllPackages() {
        return rechargePackageRepository
            .findAllByEnabledTrueAndDeletedFalseAndPackagePriceGreaterThanOrderBySortOrderAsc(1L);
    }

    /**
     * 创建充值订单并调用微信下单，返回预支付信息。优先使用套餐充值，否则按自定义金额充值
     *
     * @param userId
     *            用户 ID
     * @param packageId
     *            套餐 ID（为空或小于等于 0 时走自定义充值）
     * @param customAmount
     *            自定义充值金额，单位分
     * @return 微信预支付信息
     */
    @Transactional(rollbackFor = Exception.class)
    public PrePayInfoResBo createRechargeOrder(String userId, Integer packageId, Long customAmount) {

        // 1. 确定充值金额和 token 数量
        RechargePackage rechargePackage = null;
        Long amount;
        Long llmToken;
        Long embeddingToken;
        String description;

        if (Objects.nonNull(packageId) && packageId > 0) {
            // 使用套餐充值
            rechargePackage = getPackageById(packageId);

            amount = rechargePackage.getPackagePrice();
            llmToken = rechargePackage.getLlmToken();
            embeddingToken = rechargePackage.getEmbeddingToken();
            description = "【派聪明】充值套餐：" + rechargePackage.getPackageName();
        } else {
            // 自定义充值
            if (Objects.isNull(customAmount) || customAmount <= 0) {
                throw new CustomException("充值金额无效", HttpStatus.BAD_REQUEST);
            }

            amount = customAmount;
            rechargePackage = rechargePackageRepository.findByPackagePriceAndEnabledIsTrueAndDeletedFalse(1)
                .orElseThrow(() -> new CustomException("套餐不存在", HttpStatus.BAD_REQUEST));
            // 自定义充值按内部 1 分钱基准套餐折算 token 数量。
            llmToken = amount * rechargePackage.getLlmToken();
            embeddingToken = amount * rechargePackage.getEmbeddingToken();
            description = "【派聪明】自定义充值￥" + PriceUtil.toYuanPrice(amount) + "元";
        }

        // 2. 生成业务单号
        String tradeNo = generateTradeNo(userId);

        // 3. 创建订单记录
        RechargeOrder order = new RechargeOrder(tradeNo, userId, Objects.nonNull(packageId) ? packageId : 0, amount,
            llmToken, embeddingToken, OrderStatus.NOT_PAY, description, "");

        rechargeOrderRepository.save(order);

        // 4. 调用微信支付下单
        PayOrderReq payReq = new PayOrderReq(tradeNo, description, amount.intValue());

        return wxPayService.createOrder(payReq);
    }

    /**
     * 生成唯一业务单号：R + 年月日时分秒 + 6 位用户 ID + 4 位随机数
     */
    private String generateTradeNo(String userId) {
        // 按照年月日时分秒 + 8 位随机数生成唯一的TradeId
        String date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
        String u = String.format("%06d", Long.parseLong(userId)).substring(0, 6);
        String random = String.format("%04d", new Random().nextInt(10000));
        String tradeNo = "R" + date + time + u + random;

        return tradeNo;
    }

    /**
     * 根据 ID 查询充值套餐，不存在时抛出业务异常
     */
    public RechargePackage getPackageById(Integer id) {
        return rechargePackageRepository.findById(id)
            .orElseThrow(() -> new CustomException("套餐不存在", HttpStatus.BAD_REQUEST));
    }

    /**
     * 处理微信支付回调：验签解析后更新订单状态，支付成功时为用户发放 token
     */
    public void handlePayCallback(HttpServletRequest request) {
        // 1. 解析支付回调
        PayCallbackBo callbackBo = wxPayService.payCallback(request);

        // 2. 根据回调信息更新订单状态
        String tradeNo = callbackBo.getOutTradeNo();
        RechargeOrder order = rechargeOrderRepository.findByTradeNo(tradeNo)
            .orElseThrow(() -> new CustomException("订单不存在", HttpStatus.BAD_REQUEST));

        // 3. 更新订单状态
        if (callbackBo.getPayStatus() == RechargeStatusEnum.SUCCEED) {
            order.setStatus(OrderStatus.SUCCEED);
            order.setWxTransactionId(callbackBo.getThirdTransactionId());
            order.setPayTime(LocalDateTime.ofEpochSecond(callbackBo.getSuccessTime() / 1000, 0, ZoneOffset.of("+8")));
        } else if (callbackBo.getPayStatus() == RechargeStatusEnum.FAIL) {
            order.setStatus(OrderStatus.FAIL);
        } else {
            order.setStatus(OrderStatus.PAYING);
        }

        rechargeOrderRepository.save(order);

        // 4. 支付成功，增加用户的剩余token数量
        if (callbackBo.getPayStatus() == RechargeStatusEnum.SUCCEED) {
            this.paySuccessCallback(order);
        }
    }

    /**
     * 支付成功后的发放逻辑，为用户增加 LLM 和 Embedding token 额度
     */
    private void paySuccessCallback(RechargeOrder rechargeOrder) {
        // 增加用户的剩余token数量
        userTokenService.addLlmTokens(rechargeOrder.getUserId(), rechargeOrder.getLlmToken());

        userTokenService.addEmbeddingTokens(rechargeOrder.getUserId(), rechargeOrder.getEmbeddingToken());
    }

    /**
     * 查询指定用户的充值订单列表
     *
     * @param userId
     *            用户 ID
     * @param status
     *            订单状态（为空时查询全部），按创建时间倒序
     * @return 订单列表
     */
    public List<RechargeOrder> getUserOrders(String userId, OrderStatus status) {
        if (Objects.nonNull(status)) {
            return rechargeOrderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status);
        }

        return rechargeOrderRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 校验订单支付状态：主动向微信查询，支付成功则同步订单状态并发放 token；否则返回本地订单信息
     */
    public RechargeOrder checkOrderPayStatus(String tradeNo) {
        // 校验订单的支付状态
        PayCallbackBo bo = wxPayService.queryOrder(tradeNo);
        if (bo.getPayStatus() == RechargeStatusEnum.SUCCEED) {
            RechargeOrder order = rechargeOrderRepository.findByTradeNo(tradeNo)
                .orElseThrow(() -> new CustomException("订单不存在", HttpStatus.BAD_REQUEST));

            order.setStatus(OrderStatus.SUCCEED);
            order.setWxTransactionId(bo.getThirdTransactionId());
            order.setPayTime(LocalDateTime.ofEpochSecond(bo.getSuccessTime() / 1000, 0, ZoneOffset.of("+8")));
            order.setUpdatedAt(LocalDateTime.now());

            rechargeOrderRepository.save(order);

            paySuccessCallback(order);

            return order;
        }

        return getOrderDetail(tradeNo);
    }

    /**
     * 根据业务单号查询订单详情，不存在时抛出业务异常
     */
    public RechargeOrder getOrderDetail(String tradeNo) {
        return rechargeOrderRepository.findByTradeNo(tradeNo)
            .orElseThrow(() -> new CustomException("订单不存在", HttpStatus.BAD_REQUEST));
    }
}