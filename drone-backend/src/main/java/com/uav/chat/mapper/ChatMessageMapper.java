package com.uav.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.uav.chat.pojo.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 未读数（t49）：deleted_by_user_ids 为 JSON 数组字符串（如 [1,52]），
     * 用逗号填充后 LIKE 匹配实现跨库（MySQL/H2）的成员排除，替代 MySQL 专用 JSON_CONTAINS。
     */
    @Select("SELECT COUNT(*) FROM chat_messages " +
            "WHERE session_id = #{sessionId} AND create_time > #{since} AND status != 2 " +
            "AND CONCAT(',', COALESCE(deleted_by_user_ids, '[]'), ',') " +
            "NOT LIKE CONCAT('%,', #{userId}, ',%')")
    int countUnread(@Param("sessionId") Long sessionId,
                    @Param("since") long since,
                    @Param("userId") Long userId);
}