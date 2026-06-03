package com.uav.chat.pojo.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageVO {

    private Long id;

    private String content;

    private Long fromUserId;

    /**
     * 消息状态：0-正常(已发送/未读)，1-已读，2-已撤回（撤回后内容不显示）
     */
    private Integer status;

    // 撤回专用字段
    private LocalDateTime recallTime;   // 撤回时间

    private LocalDateTime createTime;

    // 消息类型（文本、图片、语音等）
    private Integer msgType;
}