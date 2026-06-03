package com.uav.chat.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_user_session")
public class ChatUserSession {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private Long sessionId;
    @TableField("user_id")
    private Long userId;
    @TableField("join_time")
    private LocalDateTime joinTime;
    @TableField("last_read_time")
    private LocalDateTime lastReadTime; // 可选，最后读取消息时间
}