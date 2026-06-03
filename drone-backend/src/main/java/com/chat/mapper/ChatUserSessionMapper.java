package com.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.pojo.entity.ChatUserSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatUserSessionMapper extends BaseMapper<ChatUserSession> {
}
