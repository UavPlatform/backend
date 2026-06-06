package com.uav.chat.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.FastjsonTypeHandler;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@TableName(value = "chat_messages", autoResultMap = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("from_user_id")
    private Long fromUserId;

    @TableField("session_id")
    private Long sessionId;

    @TableField(value = "content", typeHandler = JacksonTypeHandler.class)
    private String content;

    /**
     * 消息状态：0-正常(已发送/未读)，1-已读(已废除)，2-已撤回（撤回后内容不显示），3-发送失败(?暂定)
     */
    @Builder.Default
    @TableField("status")
    private Integer status = 0;

    // 撤回专用字段
    @TableField("recall_time")
    private LocalDateTime recallTime;

    // 用户侧软删除：各自独立，不影响其他人
    @TableField(value = "deleted_by_user_ids", typeHandler = FastjsonTypeHandler.class)
    private List<Long> deletedByUserIds = new ArrayList<>();

    @Builder.Default
    @TableField("create_time")
    private LocalDateTime createTime = LocalDateTime.now();

    @Builder.Default
    @TableField("msg_type")
    private Integer msgType = 0;
}