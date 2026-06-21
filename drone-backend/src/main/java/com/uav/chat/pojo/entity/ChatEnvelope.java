package com.uav.chat.pojo.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.uav.chat.pojo.enums.MsgType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 消息信封，用于 WebSocket 和 HTTP 接口的统一消息结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatEnvelope implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 全局唯一消息ID，用于去重和确认
     */
    private String msgId;

    /**
     * 所属会话ID，用于客户端将 WebSocket 消息精确归属到会话。
     */
    private Long sessionId;

    /**
     * 消息类型：CHAT, NOTICE, ORDER, COMMAND
     */
    private MsgType msgType;

    /**
     * 毫秒时间戳
     */
    private Long timestamp;

    /**
     * 发送方用户ID（系统消息时可为 "0"）
     */
    private Long fromUserId;

    /**
     * 接收方用户ID
     */
    private Long toUserId;

    /**
     * 业务数据，根据 msgType 不同结构不同
     */
    private Map<String, Object> payload;

    /**
     * 是否为离线补推的消息（客户端可用于展示不同样式）
     */
    @Builder.Default
    private Boolean isOffline = false;

    /**
     * 是否需要客户端发送已读回执
     */
    @Builder.Default
    private Boolean needAck = false;
}
