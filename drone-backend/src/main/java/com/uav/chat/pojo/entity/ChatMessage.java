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

import java.util.ArrayList;
import java.util.List;

@Data
@TableName(value = "chat_messages", autoResultMap = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;                 // 数据库自增主键，仅内部使用

    @TableField("msg_id")
    private String msgId;            // 业务唯一ID（雪花算法生成字符串）

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
    private Long recallTime;

    // 用户侧软删除：各自独立，不影响其他人
    @TableField(value = "deleted_by_user_ids", typeHandler = JacksonTypeHandler.class)
    private List<Long> deletedByUserIds = new ArrayList<>();

    @Builder.Default
    @TableField("create_time")
    private Long createTime = System.currentTimeMillis();

    @Builder.Default
    @TableField("msg_type")
    private Integer msgType = 0;
}