package com.jingluo.paismart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 14:37
 * @Desc: 文件分片信息实体：记录每个分片的序号、MD5 校验值与存储路径
 */
@Data
@Entity
@Table(name = "chunk_info",
    uniqueConstraints = @UniqueConstraint(name = "uk_file_md5_chunk_index", columnNames = {"file_md5", "chunk_index"}))
public class ChunkInfo {

    /**
     * 分块信息的唯一标识符 由数据库自动生成，用于唯一确定一个分块信息
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 文件的MD5值 用于标识一个文件，同一个文件的MD5值相同，不同文件的MD5值不同
     */
    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    /**
     * 分块的索引号 表示文件中的第几个分块，用于保持分块的顺序
     */
    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    /**
     * 分块的MD5值 每个分块的唯一标识，用于校验分块的完整性和正确性
     */
    @Column(name = "chunk_md5", nullable = false, length = 32)
    private String chunkMd5;

    /**
     * 分块的存储路径 表示分块在系统中的存储位置，可以是绝对路径或相对路径
     */
    @Column(name = "storage_path", nullable = false, length = 255)
    private String storagePath;
}
