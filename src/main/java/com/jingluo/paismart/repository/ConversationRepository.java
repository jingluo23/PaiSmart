package com.jingluo.paismart.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.model.Conversation;

/**
 * @Author: 鲸落
 * @Date: 2026/9/16 10:11
 * @Desc: 对话记录数据访问层，提供按用户、时间范围查询对话记录的功能
 */
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * 查询指定用户在时间区间内的对话记录，按时间正序排列
     *
     * @param userId
     *            用户ID
     * @param startDate
     *            开始时间
     * @param endDate
     *            结束时间
     * @return 对话记录列表
     */
    @EntityGraph(attributePaths = "user")
    List<Conversation> findByUserIdAndTimestampBetweenOrderByTimestampAsc(Long userId, LocalDateTime startDate,
        LocalDateTime endDate);

    /**
     * 查询指定用户的全部对话记录，按时间正序排列
     *
     * @param userId
     *            用户ID
     * @return 对话记录列表
     */
    @EntityGraph(attributePaths = "user")
    List<Conversation> findByUserIdOrderByTimestampAsc(Long userId);

    /**
     * 查询时间区间内所有用户的对话记录，按时间正序排列
     *
     * @param startDate
     *            开始时间
     * @param endDate
     *            结束时间
     * @return 对话记录列表
     */
    @EntityGraph(attributePaths = "user")
    List<Conversation> findByTimestampBetweenOrderByTimestampAsc(LocalDateTime startDate, LocalDateTime endDate);

    /**
     * 查询全部对话记录，按时间正序排列
     *
     * @return 对话记录列表
     */
    @EntityGraph(attributePaths = "user")
    List<Conversation> findAllByOrderByTimestampAsc();
}
