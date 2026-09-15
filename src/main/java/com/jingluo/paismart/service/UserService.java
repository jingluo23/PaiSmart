package com.jingluo.paismart.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.unit.DataSize;

import com.jingluo.paismart.enums.Role;
import com.jingluo.paismart.exception.CustomException;
import com.jingluo.paismart.model.OrganizationTag;
import com.jingluo.paismart.model.User;
import com.jingluo.paismart.repository.OrganizationTagRepository;
import com.jingluo.paismart.repository.UserRepository;
import com.jingluo.paismart.utils.PasswordUtil;

import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author: 鲸落
 * @Date: 2026/9/15 15:23
 * @Desc: 用户服务，提供管理员账号创建、密码校验及组织标签管理逻辑
 */
@Slf4j
@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    /** 系统全局上传文件大小限制，读取自 spring.servlet.multipart.max-file-size 配置 */
    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private String globalUploadMaxFileSize;

    /**
     * 密码格式：6-18 位，必须同时包含字母和数字
     */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{6,18}$");

    /** 私人标签前缀，以 "PRIVATE_" + 用户名 命名的标签属于用户私人标签，分配标签时不可被移除 */
    private static final String PRIVATE_TAG_PREFIX = "PRIVATE_";

    /** 标签 ID 最大长度 */
    private static final int MAX_TAG_ID_LENGTH = 255;

    /** 生成标签 slug 时匹配非字母数字字符 */
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9]+");

    /** 生成标签 slug 时去除首尾连字符 */
    private static final Pattern TRIM_DASH_PATTERN = Pattern.compile("(^-+|-+$)");

    /** 每 MB 对应的字节数 */
    private static final long BYTES_PER_MB = 1024L * 1024L;

    /** 系统默认组织标签 ID，不允许删除 */
    private static final String DEFAULT_ORG_TAG = "DEFAULT";

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

    /**
     * 创建组织标签：校验创建者管理员权限、标签 ID 唯一性（显式指定的 ID 不允许以 PRIVATE_ 开头）及父标签存在性， 标签 ID 为空时根据名称自动生成，创建成功后清除有效标签缓存
     *
     * @param tagId
     *            标签唯一标识，为空时自动生成
     * @param name
     *            标签名称
     * @param description
     *            标签描述
     * @param parentTag
     *            父标签 ID，为空表示顶级标签
     * @param uploadMaxSizeMb
     *            上传文件大小上限（MB），为空表示不限制
     * @param creatorUsername
     *            执行创建操作的管理员用户名
     * @return 创建成功的组织标签实体
     */
    public OrganizationTag createOrganizationTag(String tagId, String name, String description, String parentTag,
        Long uploadMaxSizeMb, String creatorUsername) {
        // 验证创建者是否为管理员
        User creator = userRepository.findByUsername(creatorUsername)
            .orElseThrow(() -> new CustomException("未找到管理员", HttpStatus.NOT_FOUND));

        if (creator.getRole() != Role.ADMIN) {
            throw new CustomException("只有管理员才能创建组织标签", HttpStatus.FORBIDDEN);
        }

        String resolvedTagId = resolveOrGenerateTagId(tagId, name);

        // 如果指定了父标签，检查父标签是否存在
        if (StringUtils.isNotBlank(parentTag)) {
            organizationTagRepository.findByTagId(parentTag)
                .orElseThrow(() -> new CustomException("未找到父标签", HttpStatus.NOT_FOUND));
        }

        OrganizationTag tag = new OrganizationTag(resolvedTagId, name, description, parentTag,
            normalizeUploadMaxSizeBytes(uploadMaxSizeMb), creator);

        OrganizationTag savedTag = organizationTagRepository.save(tag);

        // 清除标签缓存，因为层级关系可能变化
        orgTagCacheService.invalidateAllEffectiveTagsCache();

        return savedTag;
    }

    /**
     * 校验上传大小上限并转换为字节：必须大于 0 且不超过系统全局上传限制，为空表示不限制
     *
     * @param uploadMaxSizeMb
     *            上传文件大小上限（MB）
     * @return 对应的字节数，入参为 null 时返回 null
     */
    private Long normalizeUploadMaxSizeBytes(Long uploadMaxSizeMb) {
        if (Objects.isNull(uploadMaxSizeMb)) {
            return null;
        }

        if (uploadMaxSizeMb <= 0) {
            throw new CustomException("上传大小上限必须大于 0 MB", HttpStatus.BAD_REQUEST);
        }

        long uploadMaxSizeBytes = uploadMaxSizeMb * BYTES_PER_MB;
        long globalUploadMaxBytes = DataSize.parse(globalUploadMaxFileSize).toBytes();
        if (uploadMaxSizeBytes > globalUploadMaxBytes) {
            throw new CustomException("上传大小上限不能超过系统全局限制 " + (globalUploadMaxBytes / BYTES_PER_MB) + " MB",
                HttpStatus.BAD_REQUEST);
        }

        return uploadMaxSizeBytes;
    }

    /**
     * 解析标签 ID：显式指定时校验不得以 PRIVATE_ 开头且不与已有标签重复，否则根据名称自动生成唯一 ID
     *
     * @param tagId
     *            请求中指定的标签 ID，可为空
     * @param name
     *            标签名称，用于自动生成 ID
     * @return 最终使用的标签 ID
     */
    private String resolveOrGenerateTagId(String tagId, String name) {
        String normalizedTagId = StringUtils.isBlank(tagId) ? "" : tagId.trim();
        if (StringUtils.isNotBlank(normalizedTagId)) {
            if (normalizedTagId.startsWith(PRIVATE_TAG_PREFIX)) {
                throw new CustomException("Tag ID cannot start with PRIVATE_", HttpStatus.BAD_REQUEST);
            }

            if (organizationTagRepository.existsByTagId(normalizedTagId)) {
                throw new CustomException("Tag ID already exists", HttpStatus.BAD_REQUEST);
            }

            return normalizedTagId;
        }

        return generateUniqueTagId(name);
    }

    /**
     * 根据名称生成唯一的标签 ID：优先使用 "ORG_" + 名称 slug，重复时依次追加 "_2" ~ "_9999" 序号， 仍冲突则兜底追加 8 位随机 UUID 后缀
     *
     * @param name
     *            标签名称
     * @return 唯一的标签 ID
     */
    private String generateUniqueTagId(String name) {
        String slug = buildTagSlug(name);
        String baseId = truncateTagId("ORG_" + slug);

        if (!organizationTagRepository.existsByTagId(baseId)) {
            return baseId;
        }

        for (int i = 2; i <= 9999; i++) {
            String candidate = appendSuffix(baseId, "_" + i);
            if (!organizationTagRepository.existsByTagId(candidate)) {
                return candidate;
            }
        }

        while (true) {
            String uuidSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String candidate = appendSuffix(baseId, "_" + uuidSuffix);
            if (!organizationTagRepository.existsByTagId(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * 在基础 ID 后追加后缀，若拼接后超过最大长度则截断基础 ID 以保证总长不超限
     *
     * @param base
     *            基础 ID
     * @param suffix
     *            待追加的后缀
     * @return 拼接后的 ID
     */
    private String appendSuffix(String base, String suffix) {
        if (base.length() + suffix.length() <= MAX_TAG_ID_LENGTH) {
            return base + suffix;
        }

        return base.substring(0, MAX_TAG_ID_LENGTH - suffix.length()) + suffix;
    }

    /**
     * 将标签 ID 截断到最大长度以内
     *
     * @param tagId
     *            原始标签 ID
     * @return 截断后的标签 ID
     */
    private String truncateTagId(String tagId) {
        if (tagId.length() <= MAX_TAG_ID_LENGTH) {
            return tagId;
        }

        return tagId.substring(0, MAX_TAG_ID_LENGTH);
    }

    /**
     * 将标签名称转换为 slug：转小写、非字母数字字符替换为连字符、去除首尾连字符， 结果为空时返回默认值 "tag"
     *
     * @param name
     *            标签名称
     * @return 名称对应的 slug
     */
    private String buildTagSlug(String name) {
        String raw = StringUtils.isBlank(name) ? "" : name.trim().toLowerCase(Locale.ROOT);
        String slug = NON_ALNUM_PATTERN.matcher(raw).replaceAll("-");
        slug = TRIM_DASH_PATTERN.matcher(slug).replaceAll("");

        return StringUtils.isBlank(slug) ? "tag" : slug;
    }

    /**
     * 为用户分配组织标签：校验操作者权限与标签存在性，自动保留用户的私人标签不被覆盖； 用户尚无主组织时优先使用私人标签、否则取首个标签作为主组织，最后同步更新 Redis 缓存
     *
     * @param userId
     *            目标用户 ID
     * @param orgTags
     *            待分配的组织标签 ID 列表（全量覆盖，私人标签除外）
     * @param adminUsername
     *            执行分配操作的管理员用户名
     */
    public void assignOrgTagsToUser(Long userId, List<String> orgTags, String adminUsername) {
        // 验证操作者是否为管理员
        User admin = userRepository.findByUsername(adminUsername)
            .orElseThrow(() -> new CustomException("未找到管理员", HttpStatus.NOT_FOUND));

        if (admin.getRole() != Role.ADMIN) {
            throw new CustomException("只有管理员才能分配组织标签", HttpStatus.FORBIDDEN);
        }

        // 查找用户
        User user =
            userRepository.findById(userId).orElseThrow(() -> new CustomException("用户未找到", HttpStatus.NOT_FOUND));

        // 验证所有标签是否存在
        for (String tagId : orgTags) {
            if (!organizationTagRepository.existsByTagId(tagId)) {
                throw new CustomException("组织标签 " + tagId + " 未找到", HttpStatus.NOT_FOUND);
            }
        }

        // 获取用户的现有组织标签
        Set<String> existingTags = new HashSet<>();
        if (StringUtils.isNotBlank(user.getOrgTags())) {
            existingTags = Arrays.stream(user.getOrgTags().split(",")).collect(Collectors.toSet());
        }

        // 找出并保留用户的私人组织标签
        String privateTagId = PRIVATE_TAG_PREFIX + user.getUsername();
        boolean hasPrivateTag = existingTags.contains(privateTagId);

        // 确保用户的私人组织标签不会被删除
        Set<String> finalTags = new HashSet<>(orgTags);
        if (hasPrivateTag && !finalTags.contains(privateTagId)) {
            finalTags.add(privateTagId);
        }

        // 将标签列表转换为逗号分隔的字符串
        String orgTagsStr = String.join(",", finalTags);
        user.setOrgTags(orgTagsStr);

        // 如果用户没有主组织标签且有组织标签，则优先使用私人标签作为主组织
        if (StringUtils.isBlank(user.getPrimaryOrg()) && !finalTags.isEmpty()) {
            if (hasPrivateTag) {
                user.setPrimaryOrg(privateTagId);
            } else {
                user.setPrimaryOrg(new ArrayList<>(finalTags).get(0));
            }
        }

        userRepository.save(user);

        // 更新缓存
        orgTagCacheService.deleteUserOrgTagsCache(user.getUsername());

        orgTagCacheService.cacheUserOrgTags(user.getUsername(), new ArrayList<>(finalTags));

        // 同时清除有效标签缓存
        orgTagCacheService.deleteUserEffectiveTagsCache(user.getUsername());

        if (StringUtils.isNotBlank(user.getPrimaryOrg())) {
            orgTagCacheService.cacheUserPrimaryOrg(user.getUsername(), user.getPrimaryOrg());
        }
    }

    /**
     * 获取完整的组织标签树形结构，从根标签开始递归展开，无子标签的节点不返回 children 字段
     *
     * @return 标签树节点列表，每个节点包含 tagId、name、description、parentTag、上传大小限制及 children
     */
    public List<Map<String, Object>> getOrganizationTagTree() {
        // 获取所有根节点（parentTag为null的标签）
        List<OrganizationTag> rootTags = organizationTagRepository.findByParentTag(null);

        // 递归构建标签树
        return buildTagTreeRecursive(rootTags);
    }

    /**
     * 递归构建标签树：将当前层级的标签转换为树节点，并逐个查询、挂载其子标签
     *
     * @param tags
     *            当前层级的标签列表
     * @return 当前层级的树节点列表
     */
    private List<Map<String, Object>> buildTagTreeRecursive(List<OrganizationTag> tags) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (OrganizationTag tag : tags) {
            Map<String, Object> node = new HashMap<>();
            node.put("tagId", tag.getTagId());
            node.put("name", tag.getName());
            node.put("description", tag.getDescription());
            node.put("parentTag", tag.getParentTag()); // 添加父标签字段
            node.put("uploadMaxSizeBytes", tag.getUploadMaxSizeBytes());
            node.put("uploadMaxSizeMb", toUploadMaxSizeMb(tag.getUploadMaxSizeBytes()));

            // 获取子标签
            List<OrganizationTag> children = organizationTagRepository.findByParentTag(tag.getTagId());
            if (!CollectionUtils.isEmpty(children)) {
                node.put("children", buildTagTreeRecursive(children));
            }
            // 如果没有子节点，不添加children字段，而不是添加空数组

            result.add(node);
        }

        return result;
    }

    /**
     * 将上传大小上限从字节转换为 MB，便于前端展示
     *
     * @param uploadMaxSizeBytes
     *            上传大小上限（字节）
     * @return 对应的 MB 数，入参为空或非正数时返回 null
     */
    private Long toUploadMaxSizeMb(Long uploadMaxSizeBytes) {
        if (Objects.isNull(uploadMaxSizeBytes) || uploadMaxSizeBytes <= 0) {
            return null;
        }

        return uploadMaxSizeBytes / BYTES_PER_MB;
    }

    /**
     * 更新组织标签：校验操作者权限、目标标签存在性，指定新父标签时校验其存在性且不得为自身或形成层级循环； 名称与描述为空时保持原值，更新成功后清除有效标签缓存
     *
     * @param tagId
     *            待更新的标签 ID
     * @param name
     *            新标签名称，为空表示不修改
     * @param description
     *            新标签描述，为空表示不修改
     * @param parentTag
     *            新父标签 ID，为空表示移动为顶级标签
     * @param uploadMaxSizeMb
     *            上传文件大小上限（MB），为空表示不限制
     * @param adminUsername
     *            执行更新操作的管理员用户名
     * @return 更新后的组织标签实体
     */
    public OrganizationTag updateOrganizationTag(String tagId, String name, String description, String parentTag,
        Long uploadMaxSizeMb, String adminUsername) {
        // 验证操作者是否为管理员
        User admin = userRepository.findByUsername(adminUsername)
            .orElseThrow(() -> new CustomException("未找到管理员", HttpStatus.NOT_FOUND));

        if (admin.getRole() != Role.ADMIN) {
            throw new CustomException("只有管理员才能更新组织标签", HttpStatus.FORBIDDEN);
        }

        // 获取要更新的标签
        OrganizationTag tag = organizationTagRepository.findByTagId(tagId)
            .orElseThrow(() -> new CustomException("未找到组织标签", HttpStatus.NOT_FOUND));

        // 如果指定了父标签，检查父标签是否存在
        if (StringUtils.isNotBlank(parentTag)) {
            // 检查是否为自身
            if (tagId.equals(parentTag)) {
                throw new CustomException("一个标签不能成为自己的父级", HttpStatus.BAD_REQUEST);
            }

            // 检查是否存在
            organizationTagRepository.findByTagId(parentTag)
                .orElseThrow(() -> new CustomException("未找到父标签", HttpStatus.NOT_FOUND));

            // 检查是否会形成循环
            if (wouldFormCycle(tagId, parentTag)) {
                throw new CustomException("设置此父项会在标记层次结构中形成循环", HttpStatus.BAD_REQUEST);
            }
        }

        // 更新标签
        if (StringUtils.isNotBlank(name)) {
            tag.setName(name);
        }

        if (StringUtils.isNotBlank(description)) {
            tag.setDescription(description);
        }

        tag.setParentTag(parentTag);
        tag.setUploadMaxSizeBytes(normalizeUploadMaxSizeBytes(uploadMaxSizeMb));

        OrganizationTag updatedTag = organizationTagRepository.save(tag);

        // 清除所有标签缓存，因为层级关系可能变化
        orgTagCacheService.invalidateAllEffectiveTagsCache();

        return updatedTag;
    }

    /**
     * 判断将 newParentId 设置为 tagId 的父标签是否会形成循环：沿新父标签的祖先链逐级向上查找， 若途经 tagId 自身则说明会成环
     *
     * @param tagId
     *            待移动的标签 ID
     * @param newParentId
     *            新父标签 ID
     * @return true 表示会形成循环
     */
    private boolean wouldFormCycle(String tagId, String newParentId) {
        String currentParentId = newParentId;

        // 检查是否形成循环
        while (StringUtils.isNotBlank(currentParentId)) {
            if (tagId.equals(currentParentId)) {
                // 形成循环
                return Boolean.TRUE;
            }

            // 获取父标签的父标签
            Optional<OrganizationTag> parentTag = organizationTagRepository.findByTagId(currentParentId);
            if (parentTag.isEmpty()) {
                break;
            }

            currentParentId = parentTag.get().getParentTag();
        }

        return Boolean.FALSE;
    }

    /**
     * 删除组织标签：校验操作者权限，禁止删除默认标签；存在子标签、已被分配给用户或被用作主组织的标签均不允许删除， 删除成功后清除有效标签缓存
     *
     * @param tagId
     *            待删除的标签 ID
     * @param adminUsername
     *            执行删除操作的管理员用户名
     */
    public void deleteOrganizationTag(String tagId, String adminUsername) {
        // 验证操作者是否为管理员
        User admin = userRepository.findByUsername(adminUsername)
            .orElseThrow(() -> new CustomException("未找到管理员", HttpStatus.NOT_FOUND));

        if (admin.getRole() != Role.ADMIN) {
            throw new CustomException("只有管理员才能删除组织标签", HttpStatus.FORBIDDEN);
        }

        // 获取要删除的标签
        OrganizationTag tag = organizationTagRepository.findByTagId(tagId)
            .orElseThrow(() -> new CustomException("未找到组织标签", HttpStatus.NOT_FOUND));

        // 检查是否是特殊标签（如默认标签）
        if (DEFAULT_ORG_TAG.equals(tagId)) {
            throw new CustomException("Cannot delete the default organization tag", HttpStatus.BAD_REQUEST);
        }

        // 检查是否有子标签
        List<OrganizationTag> children = organizationTagRepository.findByParentTag(tagId);
        if (!children.isEmpty()) {
            throw new CustomException("无法删除默认的组织标签", HttpStatus.BAD_REQUEST);
        }

        // 检查是否有用户使用此标签
        List<User> users = userRepository.findAll();
        for (User user : users) {
            if (StringUtils.isNotBlank(user.getOrgTags())) {
                Set<String> userTags = new HashSet<>(Arrays.asList(user.getOrgTags().split(",")));
                if (userTags.contains(tagId)) {
                    throw new CustomException("无法删除已分配给用户的标签", HttpStatus.CONFLICT);
                }

                // 检查是否被用作主组织标签
                if (tagId.equals(user.getPrimaryOrg())) {
                    throw new CustomException("无法删除用作主要组织的标签", HttpStatus.CONFLICT);
                }
            }
        }

        // 检查是否有文档使用此标签（此处应检查file_upload表中的org_tag字段）
        // 由于我们没有直接访问FileUploadRepository，这里采用简化的方式检查
        // 实际实现中，应该注入FileUploadRepository并使用正确的查询方法
        try {
            // 应该是 fileUploadRepository.countByOrgTag(tagId);
            long fileCount = 0;
            if (fileCount > 0) {
                throw new CustomException("无法删除与文档关联的标签", HttpStatus.CONFLICT);
            }
        } catch (Exception e) {
            log.warn("检查标签的文件使用情况时出错: {}", tagId, e);
            throw new CustomException("无法检查文档是否使用了该标签", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // 删除标签
        organizationTagRepository.delete(tag);

        // 清除所有标签缓存，因为层级关系可能变化
        orgTagCacheService.invalidateAllEffectiveTagsCache();
    }
}
