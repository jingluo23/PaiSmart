package com.jingluo.paismart.config;

import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.model.OrganizationTag;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.OrganizationTagRepository;
import com.jingluo.paismart.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * @Author: 鲸落
 * @Date: 2026/9/21 16:57
 * @Desc: 组织标签初始化器，应用启动时预置默认组织与管理员组织两个基础标签，
 *        创建人以管理员账号为准
 */
@Slf4j
@Component
@Order(2)
public class OrgTagInitializer implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    /**
     * 管理员用户名，用于确定预置组织标签的创建人
     */
    @Value("${admin.bootstrap.username:}")
    private String adminUsername;

    /**
     * 默认组织标签ID
     */
    private static final String DEFAULT_TAG = "default";

    /**
     * 默认组织显示名称
     */
    private static final String DEFAULT_NAME = "默认组织";

    /**
     * 默认组织描述
     */
    private static final String DEFAULT_DESCRIPTION = "系统默认组织标签，自动分配给所有新用户";

    /**
     * 管理员组织标签ID
     */
    private static final String ADMIN_TAG = "admin";

    /**
     * 管理员组织显示名称
     */
    private static final String ADMIN_NAME = "管理员组织";

    /**
     * 管理员组织描述
     */
    private static final String ADMIN_DESCRIPTION = "管理员专用组织标签，具有管理权限";

    /**
     * 应用启动时执行：查找管理员作为创建人，依次预置默认组织与管理员组织标签
     *
     * @param args 启动参数
     * @throws Exception 数据库操作异常
     */
    @Override
    public void run(String... args) throws Exception {
        User adminUser = findAdminCreator();
        if (Objects.isNull(adminUser)) {
            return;
        }

        // 创建默认组织标签
        createOrganizationTagIfNotExists(DEFAULT_TAG, DEFAULT_NAME, DEFAULT_DESCRIPTION, adminUser);

        // 创建管理员组织标签
        createOrganizationTagIfNotExists(ADMIN_TAG, ADMIN_NAME, ADMIN_DESCRIPTION, adminUser);
    }

    /**
     * 按标签ID创建组织标签，已存在时跳过并输出日志
     *
     * @param tagId       标签ID
     * @param name        标签名称
     * @param description 标签描述
     * @param creator     创建人
     */
    private void createOrganizationTagIfNotExists(String tagId, String name, String description, User creator) {
        if (!organizationTagRepository.existsByTagId(tagId)) {
            OrganizationTag tag = new OrganizationTag();
            tag.setTagId(tagId);
            tag.setName(name);
            tag.setDescription(description);
            tag.setCreatedBy(creator);

            organizationTagRepository.save(tag);
        } else {
            log.warn("组织标签 '{}' 已存在，跳过创建步骤", tagId);
        }
    }

    /**
     * 查找预置组织标签的创建人：优先按配置的管理员用户名查找，未命中则取库中任意 ADMIN 角色用户
     *
     * @return 管理员用户，不存在时返回 null
     */
    private User findAdminCreator() {
        if (StringUtils.isNotBlank(adminUsername)) {
            return userRepository.findByUsername(adminUsername).orElse(null);
        }

        return userRepository.findAll().stream().filter(user -> Role.ADMIN.equals(user.getRole())).findFirst()
            .orElse(null);
    }
}
