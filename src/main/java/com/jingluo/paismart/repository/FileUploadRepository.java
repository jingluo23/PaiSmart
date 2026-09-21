package com.jingluo.paismart.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.jingluo.paismart.model.FileUpload;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 10:25
 * @Desc: 文件上传记录数据访问层，提供按上传状态查询文件记录的功能
 */
@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {

    /**
     * 按文件 MD5 与用户查询最近一条文件上传记录
     *
     * @param fileMd5
     *            文件 MD5
     * @param userId
     *            用户 ID
     * @return 最新的文件上传记录
     */
    Optional<FileUpload> findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(String fileMd5, String userId);

    /**
     * 按文件 MD5 查询最近一条文件上传记录（不限用户）
     *
     * @param fileMd5
     *            文件 MD5
     * @return 最新的文件上传记录
     */
    Optional<FileUpload> findFirstByFileMd5OrderByCreatedAtDesc(String fileMd5);

    /**
     * 条件状态更新：仅当记录当前状态等于期望状态时才更新为新状态（乐观锁语义）， 用于防止并发合并等场景下的状态竞争
     *
     * @param id
     *            文件记录 ID
     * @param currentStatus
     *            期望的当前状态
     * @param newStatus
     *            目标状态
     * @return 受影响的行数，0 表示当前状态不匹配
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE FileUpload f SET f.status = :newStatus WHERE f.id = :id AND f.status = :currentStatus")
    int updateStatusIfCurrent(@Param("id") Long id, @Param("currentStatus") int currentStatus,
        @Param("newStatus") int newStatus);

    /**
     * 按文件指纹集合批量查询上传记录，用于检索结果补齐原始文件名
     *
     * @param md5List
     *            文件 MD5 集合
     * @return 命中的上传记录列表
     */
    List<FileUpload> findByFileMd5In(List<String> md5List);

    /**
     * 按分片合并时间倒序查询第一条上传记录，用于统计知识库最近更新时间
     *
     * @return 合并时间最新的上传记录，无记录时为 empty
     */
    Optional<FileUpload> findFirstByOrderByMergedAtDesc();

    /**
     * 删除指定文件的全部上传记录，用于文档删除时清理上传元数据
     *
     * @param fileMd5
     *            文件 MD5
     * @return 删除的记录数
     */
    @Transactional
    @Modifying
    @Query("delete from FileUpload f where f.fileMd5 = :fileMd5")
    int deleteByFileMd5(@Param("fileMd5") String fileMd5);

    /**
     * 查询向量化状态为空的记录，用于定位需要回填状态的历史数据
     *
     * @return 向量化状态为空的上传记录列表
     */
    List<FileUpload> findAllByVectorizationStatusIsNull();

    /**
     * 查询用户本人上传或公开的文件，用于无组织标签用户的可访问文件列表
     *
     * @param userId
     *            用户 ID
     * @return 用户上传与公开文件的并集
     */
    List<FileUpload> findByUserIdOrIsPublicTrue(String userId);

    /**
     * 查询用户可访问的文件：本人上传、公开文件、以及组织标签命中集合且非公开的文件
     *
     * @param userId
     *            用户 ID
     * @param orgTagList
     *            用户有效组织标签集合（含层级展开）
     * @return 可访问文件列表
     */
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<FileUpload> findAccessibleFilesWithTags(@Param("userId") String userId,
        @Param("orgTagList") List<String> orgTagList);

    /**
     * 查询用户本人上传的全部文件记录
     *
     * @param userId
     *            用户 ID
     * @return 用户上传的文件列表
     */
    List<FileUpload> findByUserId(String userId);

    /**
     * 按文件名查询最近的公开文件，用于匿名（未登录）按文件名下载/预览场景
     *
     * @param fileName
     *            文件名
     * @return 最近的同名公开文件，无记录时为 empty
     */
    Optional<FileUpload> findFirstByFileNameAndIsPublicTrueOrderByCreatedAtDesc(String fileName);

    /**
     * 按文件 MD5 查询最近的公开文件，用于匿名（未登录）按 MD5 下载/预览场景
     *
     * @param fileMd5
     *            文件 MD5
     * @return 最近的公开文件，无记录时为 empty
     */
    Optional<FileUpload> findFirstByFileMd5AndIsPublicTrueOrderByCreatedAtDesc(String fileMd5);
}
