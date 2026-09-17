package com.jingluo.paismart.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.model.ChunkInfo;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 14:35
 * @Desc: 文件分片信息数据访问层
 */
@Repository
public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {

    /**
     * 判断指定文件的某个分片信息是否已存在
     *
     * @param fileMd5
     *            文件 MD5
     * @param chunkIndex
     *            分片序号
     * @return 是否存在
     */
    boolean existsByFileMd5AndChunkIndex(String fileMd5, int chunkIndex);

    /**
     * 查询指定文件已保存的全部分片序号，按序号升序返回
     *
     * @param fileMd5
     *            文件 MD5
     * @return 分片序号列表
     */
    @Query("select c.chunkIndex from ChunkInfo c where c.fileMd5 = :fileMd5 order by c.chunkIndex asc")
    List<Integer> findChunkIndexesByFileMd5(@Param("fileMd5") String fileMd5);

    /**
     * 查询指定文件的全部分片记录，按序号升序返回
     *
     * @param fileMd5
     *            文件 MD5
     * @return 分片记录列表
     */
    List<ChunkInfo> findByFileMd5OrderByChunkIndexAsc(String fileMd5);
}
