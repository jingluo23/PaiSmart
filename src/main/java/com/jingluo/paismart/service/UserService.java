package com.jingluo.paismart.service;

import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.UserRepository;
import com.jingluo.paismart.utils.PasswordUtil;

import io.micrometer.common.util.StringUtils;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 15:23
 * @Desc: 用户服务，提供管理员账号创建与密码校验逻辑
 */
@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    /**
     * 密码格式：6-18 位，必须同时包含字母和数字
     */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{6,18}$");

    /**
     * 创建管理员账号：校验创建者必须为已存在的管理员、用户名不重复、密码符合格式要求， 密码使用 BCrypt 加密后入库
     *
     * @param username
     *            新管理员用户名
     * @param password
     *            新管理员密码（明文）
     * @param creatorUsername
     *            执行创建操作的管理员用户名
     */
    public void createAdminUser(String username, String password, String creatorUsername) {
        // 验证创建者是否为管理员
        User creator = userRepository.findByUsername(creatorUsername)
            .orElseThrow(() -> new CustomException("未找到管理员", HttpStatus.NOT_FOUND));

        if (creator.getRole() != Role.ADMIN) {
            throw new CustomException("只有管理员才可以创建管理员账号", HttpStatus.FORBIDDEN);
        }

        // 检查数据库中是否已存在该用户名
        if (userRepository.findByUsername(username).isPresent()) {
            throw new CustomException("用户名已存在，请重新输入", HttpStatus.BAD_REQUEST);
        }

        validatePassword(password);

        User adminUser = new User(username, PasswordUtil.encode(password), Role.ADMIN);

        userRepository.save(adminUser);
    }

    /**
     * 校验密码格式：6-18 位字符，必须同时包含字母和数字
     *
     * @param password
     *            待校验的明文密码
     */
    private void validatePassword(String password) {
        if (StringUtils.isBlank(password) || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new CustomException("密码格式不正确，6-18位字符，必须包含字母和数字", HttpStatus.BAD_REQUEST);
        }
    }
}
