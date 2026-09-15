package com.jingluo.paismart.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.InviteCode;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.InviteCodeRepository;
import com.jingluo.paismart.repository.UserRepository;

import io.micrometer.common.util.StringUtils;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 15:36
 * @Desc: 邀请码服务，负责邀请码的批量创建与唯一性校验
 */
@Service
public class InviteCodeService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InviteCodeRepository inviteCodeRepository;

    /**
     * 邀请码可用字符集（去除易混淆的 I、O、0、1）
     */
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 批量创建邀请码
     * <p>
     * 仅管理员可创建。支持指定自定义邀请码（仅限单个创建时）、最大可用次数和有效期，未指定的参数使用默认值。
     *
     * @param creatorUsername
     *            创建人用户名（须为管理员）
     * @param requestedCode
     *            自定义邀请码（可选，仅当批量数量为 1 时生效）
     * @param maxUses
     *            每个邀请码的最大可用次数（可选，默认 1）
     * @param expiresAt
     *            过期时间（可选，为空表示永不过期，须晚于当前时间）
     * @param count
     *            批量创建数量（可选，默认 1，最大 100）
     * @return 创建成功的邀请码列表
     */
    public List<InviteCode> createInviteCodes(String creatorUsername, String requestedCode, Integer maxUses,
        LocalDateTime expiresAt, Integer count) {
        User creator = userRepository.findByUsername(creatorUsername)
            .orElseThrow(() -> new CustomException("未找到邀请者", HttpStatus.NOT_FOUND));

        if (creator.getRole() != Role.ADMIN) {
            throw new CustomException("只有管理员可以创建邀请码", HttpStatus.FORBIDDEN);
        }

        int normalizedCount = Objects.isNull(count) || count <= 0 ? 1 : count;
        if (normalizedCount > 100) {
            throw new CustomException("邀请码批量大小不能超过100", HttpStatus.BAD_REQUEST);
        }

        if (normalizedCount > 1 && StringUtils.isNotBlank(requestedCode)) {
            throw new CustomException("仅当批量大小为1时，才支持自定义代码", HttpStatus.BAD_REQUEST);
        }

        int normalizedMaxUses = Objects.isNull(maxUses) || maxUses <= 0 ? 1 : maxUses;

        LocalDateTime normalizedExpiresAt = expiresAt;
        if (Objects.nonNull(normalizedExpiresAt) && normalizedExpiresAt.isBefore(LocalDateTime.now())) {
            throw new CustomException("邀请码的有效期必须设定在将来", HttpStatus.BAD_REQUEST);
        }

        List<InviteCode> inviteCodes = new ArrayList<>(normalizedCount);
        Set<String> generatedCodes = new HashSet<>();

        for (int i = 0; i < normalizedCount; i++) {
            String code = resolveInviteCode(requestedCode, normalizedCount, generatedCodes);

            InviteCode inviteCode =
                new InviteCode(code, normalizedMaxUses, 0, normalizedExpiresAt, Boolean.TRUE, creator);

            inviteCodes.add(inviteCode);
            generatedCodes.add(code);
        }

        return inviteCodeRepository.saveAll(inviteCodes);
    }

    /**
     * 解析本次要使用的邀请码
     * <p>
     * 批量数量为 1 且指定了自定义邀请码时使用自定义码（需校验唯一性），否则随机生成 16 位邀请码， 并确保其不与本批次已生成的码及数据库中已有记录重复。
     *
     * @param requestedCode
     *            自定义邀请码（可选）
     * @param batchSize
     *            批量创建数量
     * @param generatedCodes
     *            本批次已生成的邀请码集合，用于去重
     * @return 可用的邀请码
     */
    private String resolveInviteCode(String requestedCode, int batchSize, Set<String> generatedCodes) {
        if (batchSize == 1 && StringUtils.isNotBlank(requestedCode)) {
            String normalized = normalizeCode(requestedCode);
            if (inviteCodeRepository.findByCode(normalized).isPresent()) {
                throw new CustomException("邀请码已存在", HttpStatus.BAD_REQUEST);
            }

            return normalized;
        }

        String generated;
        do {
            generated = generateCode(16);
        } while (generatedCodes.contains(generated) || inviteCodeRepository.findByCode(generated).isPresent());

        return generated;
    }

    /**
     * 使用安全随机数从字符集中生成指定长度的随机邀请码
     *
     * @param length
     *            邀请码长度
     * @return 随机生成的邀请码
     */
    private String generateCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_CHARS.charAt(secureRandom.nextInt(CODE_CHARS.length())));
        }

        return sb.toString();
    }

    /**
     * 校验并规范化自定义邀请码（去除首尾空白、统一转为大写）
     *
     * @param code
     *            用户输入的自定义邀请码
     * @return 规范化后的邀请码
     */
    private String normalizeCode(String code) {
        if (StringUtils.isBlank(code)) {
            throw new CustomException("邀请码不能为空", HttpStatus.BAD_REQUEST);
        }

        return code.trim().toUpperCase();
    }

    /**
     * 分页查询邀请码列表
     * <p>
     * 支持按启用状态筛选，结果按创建时间倒序排列，未指定启用状态时查询全部邀请码。
     *
     * @param enabled
     *            启用状态筛选（可选，为空表示查询全部）
     * @param page
     *            页码（从 1 开始）
     * @param size
     *            每页数量（最小 1）
     * @return 分页结果，包含记录列表、总条数、总页数、当前页码和每页数量
     */
    public Map<String, Object> list(Boolean enabled, int page, int size) {
        Pageable pageable =
            PageRequest.of(Math.max(0, page - 1), Math.max(1, size), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<InviteCode> result = Objects.isNull(enabled) ? inviteCodeRepository.findAll(pageable)
            : inviteCodeRepository.findByEnabled(enabled, pageable);

        return Map.of("records", result.getContent(), "total", result.getTotalElements(), "pages",
            result.getTotalPages(), "current", page, "size", size);
    }

    /**
     * 禁用邀请码
     * <p>
     * 仅管理员可操作，禁用后邀请码无法继续使用。
     *
     * @param id
     *            邀请码 ID
     * @param adminUsername
     *            操作人用户名（须为管理员）
     */
    public void disable(Long id, String adminUsername) {
        validateAdmin(adminUsername, "禁用");

        InviteCode inviteCode =
            inviteCodeRepository.findById(id).orElseThrow(() -> new CustomException("未找到邀请码", HttpStatus.NOT_FOUND));
        inviteCode.setEnabled(false);

        inviteCodeRepository.save(inviteCode);
    }

    /**
     * 校验操作人是否为管理员
     *
     * @param adminUsername
     *            操作人用户名
     * @param action
     *            操作名称，用于构造无权限时的提示信息
     */
    private void validateAdmin(String adminUsername, String action) {
        User admin = userRepository.findByUsername(adminUsername)
            .orElseThrow(() -> new CustomException("未找到管理员", HttpStatus.NOT_FOUND));

        if (admin.getRole() != Role.ADMIN) {
            throw new CustomException("只有管理员可以" + action + " 邀请码", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 删除邀请码
     * <p>
     * 仅管理员可操作，直接从数据库中移除该邀请码记录。
     *
     * @param id
     *            邀请码 ID
     * @param adminUsername
     *            操作人用户名（须为管理员）
     */
    public void delete(Long id, String adminUsername) {
        validateAdmin(adminUsername, "删除");

        InviteCode inviteCode =
            inviteCodeRepository.findById(id).orElseThrow(() -> new CustomException("未找到邀请码", HttpStatus.NOT_FOUND));

        inviteCodeRepository.delete(inviteCode);
    }

    /**
     * 编辑邀请码
     * <p>
     * 仅管理员可操作。已使用过的邀请码不允许编辑；新邀请码字符串会做规范化处理并通过唯一性校验。
     *
     * @param id
     *            邀请码 ID
     * @param adminUsername
     *            操作人用户名（须为管理员）
     * @param code
     *            新的邀请码字符串
     * @param maxUses
     *            新的最大可用次数（可选，默认 1）
     * @param expiresAt
     *            新的过期时间（可选，为空表示保持不变，须晚于当前时间）
     * @return 更新后的邀请码
     */
    public InviteCode update(Long id, String adminUsername, String code, Integer maxUses, LocalDateTime expiresAt) {
        validateAdmin(adminUsername, "编辑");

        InviteCode inviteCode =
            inviteCodeRepository.findById(id).orElseThrow(() -> new CustomException("未找到邀请码", HttpStatus.NOT_FOUND));

        if (inviteCode.getUsedCount() > 0) {
            throw new CustomException("已使用的邀请码无法编辑", HttpStatus.BAD_REQUEST);
        }

        String normalizedCode = normalizeCode(code);
        InviteCode existingInviteCode = inviteCodeRepository.findByCode(normalizedCode).orElse(null);
        if (Objects.nonNull(existingInviteCode) && !existingInviteCode.getId().equals(id)) {
            throw new CustomException("邀请码已存在", HttpStatus.BAD_REQUEST);
        }

        int normalizedMaxUses = Objects.isNull(maxUses) || maxUses <= 0 ? 1 : maxUses;
        if (Objects.nonNull(expiresAt) && expiresAt.isBefore(LocalDateTime.now())) {
            throw new CustomException("邀请码的有效期必须设定在将来", HttpStatus.BAD_REQUEST);
        }

        inviteCode.setCode(normalizedCode);
        inviteCode.setMaxUses(normalizedMaxUses);
        inviteCode.setExpiresAt(expiresAt);

        return inviteCodeRepository.save(inviteCode);
    }
}
