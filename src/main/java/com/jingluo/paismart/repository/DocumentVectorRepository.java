package com.jingluo.paismart.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.jingluo.paismart.model.DocumentVector;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 17:35
 * @Desc: 文档分块向量数据访问层，提供分块文本内容的读写与计数能力
 */
@Repository
public interface DocumentVectorRepository extends JpaRepository<DocumentVector, Long> {

    /**
     * 删除指定文件的全部向量分块记录，用于文档删除与索引重建前清理旧数据
     *
     * @param fileMd5
     *            文件 MD5
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM document_vectors WHERE file_md5 = ?1", nativeQuery = true)
    void deleteByFileMd5(String fileMd5);

    /**
     * 按分块序号升序查询指定文件的全部向量分块记录，用于向量化时读取分块文本
     *
     * @param fileMd5
     *            文件 MD5
     * @return 向量分块记录列表
     */
    List<DocumentVector> findByFileMd5OrderByChunkIdAsc(String fileMd5);

    /**
     * 统计指定文件的向量分块记录数，用于推断历史数据的向量化状态
     *
     * @param fileMd5
     *            文件 MD5
     * @return 分块记录数
     */
    long countByFileMd5(String fileMd5);

    /**
     * 统计指定文件中带页码信息的向量分块记录数，用于校验 PDF 分块元数据是否完整
     *
     * @param fileMd5
     *            文件 MD5
     * @return 带页码的分块记录数
     */
    long countByFileMd5AndPageNumberIsNotNull(String fileMd5);
}
