package com.jingluo.paismart.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.model.DocumentVector;

/**
 * @Author: 鲸落
 * @Date: 2026/9/17 17:35
 * @Desc:
 */
@Repository
public interface DocumentVectorRepository extends JpaRepository<DocumentVector, Long> {}
