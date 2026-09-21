package com.jingluo.paismart.config;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.UserRepository;
import com.jingluo.paismart.utils.PasswordUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 15:44
 * @Desc: 管理员账号引导初始化器，应用启动时根据 bootstrap 配置自动创建初始管理员账号
 */
@Slf4j
@Component
@Order(1)
public class AdminUserInitializer implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    /**
     * 是否启用管理员账号引导创建
     */
    @Value("${admin.bootstrap.enabled:false}")
    private boolean bootstrapEnabled;

    /**
     * 初始管理员的登录用户名
     */
    @Value("${admin.bootstrap.username:}")
    private String adminUsername;

    /**
     * 初始管理员的明文密码（保存前会进行加密）
     */
    @Value("${admin.bootstrap.password:}")
    private String adminPassword;

    /**
     * 管理员的主组织标识
     */
    @Value("${admin.bootstrap.primary-org:default}")
    private String adminPrimaryOrg;

    /**
     * 管理员所属的组织标签集合（逗号分隔）
     */
    @Value("${admin.bootstrap.org-tags:default,admin}")
    private String adminOrgTags;

    /**
     * 禁止使用的弱口令列表
     */
    private static final Set<String> WEAK_PASSWORDS =
        Set.of("admin123", "admin", "password", "123456", "12345678", "qwerty");

    /**
     * 应用启动时执行：校验 bootstrap 配置，若管理员账号不存在则创建
     *
     * @param args 启动参数
     * @throws Exception 配置非法或账号创建失败时抛出异常，阻止应用继续启动
     */
    @Override
    public void run(String... args) throws Exception {
        if (!bootstrapEnabled) {
            return;
        }

        validateBootstrapConfig();

        Optional<User> existingAdmin = userRepository.findByUsername(adminUsername);

        if (existingAdmin.isPresent()) {
            return;
        }

        try {
            User adminUser = new User();
            adminUser.setUsername(adminUsername);
            adminUser.setPassword(PasswordUtil.encode(adminPassword));
            adminUser.setRole(Role.ADMIN);
            adminUser.setPrimaryOrg(adminPrimaryOrg);
            adminUser.setOrgTags(adminOrgTags);

            userRepository.save(adminUser);
        } catch (Exception e) {
            log.warn("创建管理员账号失败: {}", e.getMessage(), e);

            throw new RuntimeException("无法创建管理员账号", e);
        }
    }

    /**
     * 校验管理员引导配置的合法性：用户名密码非空、密码长度不低于12位且不为弱口令
     */
    private void validateBootstrapConfig() {
        if (StringUtils.isBlank(adminUsername)) {
            throw new IllegalStateException("admin.bootstrap.username 不能为空");
        }

        if (StringUtils.isBlank(adminPassword)) {
            throw new IllegalStateException("admin.bootstrap.password 不能为空");
        }

        if (adminPassword.length() < 12) {
            throw new IllegalStateException("admin.bootstrap.password 长度必须 >= 12");
        }

        if (WEAK_PASSWORDS.contains(adminPassword.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("admin.bootstrap.password 不能使用弱口令");
        }
    }
}
