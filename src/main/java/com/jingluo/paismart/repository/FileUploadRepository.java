package com.jingluo.paismart.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.model.FileUpload;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 10:25
 * @Desc: 文件上传记录数据访问层，提供按上传状态查询文件记录的功能
 */
@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {

}
