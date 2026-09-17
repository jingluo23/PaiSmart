package com.jingluo.paismart.repository;

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
}
