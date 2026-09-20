package com.jingluo.paismart.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingluo.paismart.model.ConversationSession;

/**
 * @Author: 鲸落
 * @Date: 2026/9/20 11:03
 * @Desc: 对话会话数据访问接口，提供按用户与逻辑会话ID的查询能力
 */
@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {

    /**
     * 查询指定用户的会话列表，按最后更新时间倒序排列
     *
     * @param userId
     *            用户ID
     * @return 该用户的会话列表，最近活跃的会话排在前面
     */
    List<ConversationSession> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * 按逻辑会话ID查询会话
     *
     * @param conversationId
     *            逻辑会话ID
     * @return 匹配的会话，不存在时返回 Optional.empty()
     */
    Optional<ConversationSession> findByConversationId(String conversationId);

    /**
     * 判断逻辑会话ID是否已存在
     *
     * @param conversationId
     *            逻辑会话ID
     * @return 存在返回 true，否则返回 false
     */
    boolean existsByConversationId(String conversationId);
}
