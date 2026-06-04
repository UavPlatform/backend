package com.chat.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("chat_session")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;                 // 会话名称

    @TableField("type")
    private Integer type;                // 0: 一对一，1: 多人聊天室/群组

    // 使用 JacksonTypeHandler 自动映射 JSON 到 List<Long>
    @TableField(value = "user_ids", typeHandler = JacksonTypeHandler.class)
    private List<Long> userIds;          // 会话成员用户ID列表

    @TableField("owner_id")
    private Long ownerId;                // 群主/创建者ID

    @TableField("avatar")
    private String avatar;               // 会话头像

    @TableField("description")
    private String description;          // 会话描述

    @TableField("create_time")
    private Long createTime = System.currentTimeMillis(); // 群聊创建时间
}