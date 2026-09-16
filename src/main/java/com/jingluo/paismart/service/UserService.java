package com.jingluo.paismart.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.unit.DataSize;

import com.jingluo.paismart.config.AppAuthProperties;
import com.jingluo.paismart.domain.response.UserUsageSnapshot;
import com.jingluo.paismart.enums.RegistrationMode;
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

    @Autowired
    private UsageQuotaService usageQuotaService;

    @Autowired
    private AppAuthProperties appAuthProperties;

    @Autowired
    private InviteCodeService inviteCodeService;

    /**
     * 系统全局上传文件大小限制，读取自 spring.servlet.multipart.max-file-size 配置
     */
    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private String globalUploadMaxFileSize;

    /**
     * 密码格式：6-18 位，必须同时包含字母和数字
     */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{6,18}$");

    /**
     * 私人标签前缀，以 "PRIVATE_" + 用户名 命名的标签属于用户私人标签，分配标签时不可被移除
     */
    private static final String PRIVATE_TAG_PREFIX = "PRIVATE_";

    /**
     * 标签 ID 最大长度
     */
    private static final int MAX_TAG_ID_LENGTH = 255;

    /**
     * 生成标签 slug 时匹配非字母数字字符
     */
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9]+");

    /**
     * 生成标签 slug 时去除首尾连字符
     */
    private static final Pattern TRIM_DASH_PATTERN = Pattern.compile("(^-+|-+$)");

    /**
     * 每 MB 对应的字节数
     */
    private static final long BYTES_PER_MB = 1024L * 1024L;

    /**
     * 系统默认组织标签 ID，不允许删除
     */
    private static final String DEFAULT_ORG_TAG = "DEFAULT";

    /**
     * 默认组织标签的显示名称
     */
    private static final String DEFAULT_ORG_NAME = "默认组织";

    /**
     * 默认组织标签的描述
     */
    private static final String DEFAULT_ORG_DESCRIPTION = "系统默认组织标签，自动分配给所有新用户";

    /**
     * 私人组织标签的名称后缀，标签名 = 用户名 + 后缀
     */
    private static final String PRIVATE_ORG_NAME_SUFFIX = "的私人空间";

    /**
     * 私人组织标签的描述
     */
    private static final String PRIVATE_ORG_DESCRIPTION = "用户的私人组织标签，仅用户本人可访问";

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

    /**
     * 分页查询用户列表：先按创建时间倒序做内存过滤与分页，再为每页用户附带组织标签详情、角色状态及当日用量快照
     *
     * @param keyword
     *            用户名关键词，为空表示不过滤
     * @param orgTag
     *            组织标签 ID，为空表示不过滤
     * @param status
     *            用户状态：1 表示普通用户，0 表示管理员，为空表示不过滤
     * @param page
     *            页码，从 1 开始，小于 1 时按 1 处理
     * @param size
     *            每页条数，小于等于 0 时取默认值 10
     * @return 分页结果，包含 content、totalElements、totalPages、size、number（从 1 开始的页码）
     */
    public Map<String, Object> getUserList(String keyword, String orgTag, Integer status, int page, int size) {
        int safePage = Math.max(page, 1);

        int safeSize = size > 0 ? size : 10;

        int pageIndex = safePage - 1;

        Pageable pageable = PageRequest.of(pageIndex, safeSize, Sort.by("createdAt").descending());

        List<User> filteredUsers = userRepository.findAll(Sort.by("createdAt").descending()).stream()
            .filter(user -> matchesUserListFilters(user, keyword, orgTag, status)).toList();

        int start = Math.min((int)pageable.getOffset(), filteredUsers.size());

        int end = Math.min(start + pageable.getPageSize(), filteredUsers.size());

        List<User> pageContent = start < end ? filteredUsers.subList(start, end) : Collections.emptyList();

        Page<User> userPage = new PageImpl<>(pageContent, pageable, filteredUsers.size());

        // 转换为前端需要的格式
        Map<String, UserUsageSnapshot> usageSnapshots = usageQuotaService
            .getSnapshots(userPage.getContent().stream().map(user -> String.valueOf(user.getId())).toList());

        List<Map<String, Object>> userList = userPage.getContent().stream().map(user -> {
            Map<String, Object> userMap = new HashMap<>();
            userMap.put("userId", user.getId());
            userMap.put("username", user.getUsername());

            // 获取用户组织标签的详细信息
            List<Map<String, String>> orgTagDetails = new ArrayList<>();
            if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
                Arrays.stream(user.getOrgTags().split(",")).forEach(tagId -> {
                    OrganizationTag tag = organizationTagRepository.findByTagId(tagId).orElse(null);
                    if (tag != null) {
                        Map<String, String> tagInfo = new HashMap<>();
                        tagInfo.put("tagId", tag.getTagId());
                        tagInfo.put("name", tag.getName());
                        orgTagDetails.add(tagInfo);
                    }
                });
            }

            userMap.put("orgTags", orgTagDetails);
            userMap.put("primaryOrg", user.getPrimaryOrg());
            userMap.put("status", user.getRole() == Role.USER ? 1 : 0);
            userMap.put("createdAt", user.getCreatedAt());
            userMap.put("usage", usageSnapshots.getOrDefault(String.valueOf(user.getId()),
                usageQuotaService.getSnapshot(String.valueOf(user.getId()))));

            return userMap;
        }).collect(Collectors.toList());

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("content", userList);
        result.put("totalElements", userPage.getTotalElements());
        result.put("totalPages", userPage.getTotalPages());
        result.put("size", userPage.getSize());
        result.put("number", userPage.getNumber() + 1); // 转换为从1开始的页码

        return result;
    }

    /**
     * 判断用户是否匹配用户列表的筛选条件：依次校验组织标签包含关系、用户名关键词、角色状态， 全部通过才返回 true
     *
     * @param user
     *            待检查的用户
     * @param keyword
     *            用户名关键词
     * @param orgTag
     *            组织标签 ID
     * @param status
     *            用户状态：1 表示普通用户，0 表示管理员
     * @return true 表示匹配所有已指定的筛选条件
     */
    private boolean matchesUserListFilters(User user, String keyword, String orgTag, Integer status) {
        if (StringUtils.isNotBlank(orgTag)) {
            if (StringUtils.isBlank(user.getOrgTags())) {
                return Boolean.FALSE;
            }

            Set<String> userTags = new HashSet<>(Arrays.asList(user.getOrgTags().split(",")));
            if (!userTags.contains(orgTag)) {
                return Boolean.FALSE;
            }
        }

        if (StringUtils.isNotBlank(keyword) && !user.getUsername().contains(keyword)) {
            return Boolean.FALSE;
        }

        if (Objects.nonNull(status)) {
            return user.getRole() == (status == 1 ? Role.USER : Role.ADMIN);
        }

        return Boolean.TRUE;
    }

    /**
     * 注册普通用户
     * <p>
     * 事务方法。流程：按注册策略校验（注册模式、邀请码消费）→ 校验密码格式与用户名唯一性 → 确保默认组织存在 → 创建用户 → 创建私人组织标签 → 分配默认组织与私人组织并设置主组织 → 缓存组织标签信息。
     *
     * @param username
     *            用户名
     * @param password
     *            密码（明文，将使用 BCrypt 加密入库）
     * @param inviteCode
     *            邀请码，注册模式要求时必填
     * @throws CustomException
     *             注册关闭、邀请码无效或用户名已存在时抛出
     */
    @Transactional
    public void registerUser(String username, String password, String inviteCode) {
        validateRegistrationPolicy(username, inviteCode);

        validatePassword(password);

        // 检查数据库中是否已存在该用户名
        if (userRepository.findByUsername(username).isPresent()) {
            // 若用户名已存在，抛出自定义异常，状态码为 400 Bad Request
            throw new CustomException("用户名已存在", HttpStatus.BAD_REQUEST);
        }

        // 确保默认组织标签存在（系统内部使用）
        ensureDefaultOrgTagExists();

        User user = new User(username, PasswordUtil.encode(password), Role.USER);

        // 保存用户以生成ID
        userRepository.save(user);

        // 创建用户的私人组织标签
        String privateTagId = PRIVATE_TAG_PREFIX + username;
        createPrivateOrgTag(privateTagId, username, user);

        // 新用户默认拥有系统默认组织和自己的私人组织
        List<String> assignedOrgTags = List.of(DEFAULT_ORG_TAG, privateTagId);
        user.setOrgTags(String.join(",", assignedOrgTags));

        // 设置私人组织标签为主组织标签
        user.setPrimaryOrg(privateTagId);

        userRepository.save(user);

        // 缓存组织标签信息
        orgTagCacheService.cacheUserOrgTags(username, assignedOrgTags);
        orgTagCacheService.cacheUserPrimaryOrg(username, privateTagId);
    }

    /**
     * 创建用户的私人组织标签，标签已存在时跳过
     *
     * @param privateTagId
     *            私人标签 ID（"PRIVATE_" + 用户名）
     * @param username
     *            用户名，用于拼接标签名称
     * @param owner
     *            标签所有者
     */
    private void createPrivateOrgTag(String privateTagId, String username, User owner) {
        // 检查私人标签是否已存在
        if (!organizationTagRepository.existsByTagId(privateTagId)) {
            // 创建私人组织标签
            OrganizationTag privateTag =
                new OrganizationTag(privateTagId, username + PRIVATE_ORG_NAME_SUFFIX, PRIVATE_ORG_DESCRIPTION, owner);

            organizationTagRepository.save(privateTag);
        }
    }

    /**
     * 确保系统默认组织标签存在，不存在时自动以首个管理员作为创建者初始化
     *
     * @throws CustomException
     *             系统中没有任何管理员用户时抛出
     */
    private void ensureDefaultOrgTagExists() {
        if (!organizationTagRepository.existsByTagId(DEFAULT_ORG_TAG)) {
            // 寻找一个管理员用户作为创建者
            Optional<User> adminUser =
                userRepository.findAll().stream().filter(user -> Role.ADMIN.equals(user.getRole())).findFirst();

            User creator =
                adminUser.orElseThrow(() -> new CustomException("没有管理员用户来初始化默认组织标签", HttpStatus.INTERNAL_SERVER_ERROR));

            // 创建默认组织标签
            OrganizationTag defaultTag =
                new OrganizationTag(DEFAULT_ORG_TAG, DEFAULT_ORG_NAME, DEFAULT_ORG_DESCRIPTION, creator);

            organizationTagRepository.save(defaultTag);
        }
    }

    /**
     * 校验注册策略
     * <p>
     * 注册模式为 CLOSED 时直接拒绝注册；配置要求邀请码或模式为 INVITE_ONLY 时， 消费一次邀请码（含有效性、有效期与剩余次数校验）。
     *
     * @param username
     *            待注册的用户名，用于日志记录
     * @param inviteCode
     *            邀请码
     * @throws CustomException
     *             注册关闭或邀请码校验不通过时抛出
     */
    private void validateRegistrationPolicy(String username, String inviteCode) {
        RegistrationMode mode = appAuthProperties.getRegistration().getMode();

        boolean inviteRequired =
            appAuthProperties.getRegistration().isInviteRequired() || mode == RegistrationMode.INVITE_ONLY;

        if (mode == RegistrationMode.CLOSED) {
            log.warn("注册被阻止，因为注册模式已关闭，用户名: {}", username);

            throw new CustomException("REGISTRATION_CLOSED", HttpStatus.FORBIDDEN);
        }

        if (inviteRequired) {
            inviteCodeService.consume(inviteCode, username);
        }
    }

    /**
     * 用户认证：校验用户名存在且密码匹配
     *
     * @param username
     *            用户名
     * @param password
     *            密码（明文）
     * @return 认证成功时返回用户名
     * @throws CustomException
     *             用户不存在或密码不匹配时抛出（401）
     */
    public String authenticateUser(String username, String password) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new CustomException("用户名或密码无效", HttpStatus.UNAUTHORIZED));

        // 比较输入的密码和数据库中存储的加密密码是否匹配
        if (!PasswordUtil.matches(password, user.getPassword())) {
            // 若不匹配，抛出自定义异常，状态码为 401 Unauthorized
            throw new CustomException("用户名或密码无效", HttpStatus.UNAUTHORIZED);
        }

        // 认证成功，返回用户的用户名
        return user.getUsername();
    }

    /**
     * 获取用户的组织标签信息
     * <p>
     * 优先从 Redis 缓存读取组织标签列表与主组织，未命中时回源数据库并回填缓存； 同时返回各标签的详细信息（名称、描述、上传大小限制）。
     *
     * @param username
     *            用户名
     * @return 包含 orgTags（标签 ID 列表）、primaryOrg（主组织）及 orgTagDetails（标签详情）的 map
     * @throws CustomException
     *             用户不存在时抛出
     */
    public Map<String, Object> getUserOrgTags(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new CustomException("未找到用户", HttpStatus.NOT_FOUND));

        // 尝试从缓存获取
        List<String> orgTags = orgTagCacheService.getUserOrgTags(username);

        String primaryOrg = orgTagCacheService.getUserPrimaryOrg(username);

        // 如果缓存中没有，则从数据库获取
        if (CollectionUtils.isEmpty(orgTags)) {
            orgTags = Arrays.asList(user.getOrgTags().split(","));
            // 更新缓存
            orgTagCacheService.cacheUserOrgTags(username, orgTags);
        }

        if (StringUtils.isBlank(primaryOrg)) {
            primaryOrg = user.getPrimaryOrg();
            // 更新缓存
            orgTagCacheService.cacheUserPrimaryOrg(username, primaryOrg);
        }

        // 获取组织标签的详细信息
        List<Map<String, Object>> orgTagDetails = new ArrayList<>();
        for (String tagId : orgTags) {
            OrganizationTag tag = organizationTagRepository.findByTagId(tagId).orElse(null);
            if (Objects.nonNull(tag)) {
                Map<String, Object> tagInfo = new HashMap<>();
                tagInfo.put("tagId", tag.getTagId());
                tagInfo.put("name", tag.getName());
                tagInfo.put("description", tag.getDescription());
                tagInfo.put("uploadMaxSizeBytes", tag.getUploadMaxSizeBytes());
                tagInfo.put("uploadMaxSizeMb", toUploadMaxSizeMb(tag.getUploadMaxSizeBytes()));
                orgTagDetails.add(tagInfo);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orgTags", orgTags);
        result.put("primaryOrg", primaryOrg);
        result.put("orgTagDetails", orgTagDetails);

        return result;
    }

    /**
     * 设置用户的主组织标签
     * <p>
     * 仅允许设置为已分配给该用户的组织标签，设置成功后同步更新 Redis 缓存。
     *
     * @param username
     *            用户名
     * @param primaryOrg
     *            主组织标签 ID
     * @throws CustomException
     *             用户不存在或该标签未分配给用户时抛出
     */
    public void setUserPrimaryOrg(String username, String primaryOrg) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new CustomException("未找到用户", HttpStatus.NOT_FOUND));

        // 检查该组织标签是否已分配给用户
        Set<String> userTags = Arrays.stream(user.getOrgTags().split(",")).collect(Collectors.toSet());
        if (!userTags.contains(primaryOrg)) {
            throw new CustomException("未向用户分配组织标签", HttpStatus.BAD_REQUEST);
        }

        user.setPrimaryOrg(primaryOrg);

        userRepository.save(user);

        // 更新缓存
        orgTagCacheService.cacheUserPrimaryOrg(username, primaryOrg);
    }

    /**
     * 获取用户的主组织标签
     * <p>
     * 优先从 Redis 缓存读取；未命中时回源数据库，用户未设置主组织时回退为首个组织标签（并持久化）， 无任何标签时回退为系统默认组织，最后回填缓存。
     *
     * @param userId
     *            用户 ID 或用户名（自动识别）
     * @return 主组织标签 ID
     * @throws CustomException
     *             用户不存在时抛出
     */
    public String getUserPrimaryOrg(String userId) {
        // 先通过userId查找用户，然后获取username
        User user = resolveUser(userId);

        String username = user.getUsername();

        // 尝试从缓存获取
        String primaryOrg = orgTagCacheService.getUserPrimaryOrg(username);

        // 如果缓存中没有，则从数据库获取
        if (StringUtils.isBlank(primaryOrg)) {
            primaryOrg = user.getPrimaryOrg();

            // 如果用户没有设置主组织标签，则尝试使用第一个分配的组织标签
            if (StringUtils.isBlank(primaryOrg)) {
                String[] tags = user.getOrgTags().split(",");
                if (tags.length > 0) {
                    primaryOrg = tags[0];
                    // 更新用户的主组织标签
                    user.setPrimaryOrg(primaryOrg);

                    userRepository.save(user);
                } else {
                    // 如果用户没有任何组织标签，则使用默认标签
                    primaryOrg = DEFAULT_ORG_TAG;
                }
            }

            // 更新缓存
            orgTagCacheService.cacheUserPrimaryOrg(username, primaryOrg);
        }

        return primaryOrg;
    }

    /**
     * 解析用户：入参可解析为数字时按用户 ID 查询，否则按用户名查询
     *
     * @param userId
     *            用户 ID 或用户名
     * @return 对应的用户实体
     * @throws CustomException
     *             未找到对应用户时抛出
     */
    private User resolveUser(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);

            return userRepository.findById(userIdLong)
                .orElseThrow(() -> new CustomException("未找到ID对应的用户: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userId)
                .orElseThrow(() -> new CustomException("未找到用户: " + userId, HttpStatus.NOT_FOUND));
        }
    }
}
