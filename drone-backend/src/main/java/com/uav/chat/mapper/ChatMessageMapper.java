package com.uav.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.uav.chat.pojo.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    @Select("SELECT COUNT(*) FROM chat_messages " +
            "WHERE session_id = #{sessionId} AND create_time > #{since} AND status != 2 " +
            "AND NOT JSON_CONTAINS(deleted_by_user_ids, CAST(#{userId} AS CHAR), '$')")
    int countUnread(@Param("sessionId") Long sessionId,
                    @Param("since") long since,
                    @Param("userId") Long userId);
}