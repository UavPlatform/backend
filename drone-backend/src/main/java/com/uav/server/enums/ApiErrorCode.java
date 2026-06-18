package com.uav.server.enums;

import lombok.Getter;

@Getter
public enum ApiErrorCode {
    INVALID_PARAM("INVALID_PARAM", "请求参数不合法"),
    UAV_NOT_FOUND("UAV_NOT_FOUND", "无人机不存在"),
    UAV_NOT_CONNECTED("UAV_NOT_CONNECTED", "无人机未建立 WebSocket 连接"),
    UAV_STATUS_NOT_FOUND("UAV_STATUS_NOT_FOUND", "无人机暂未上报状态"),
    LIVE_ALREADY_STARTING("LIVE_ALREADY_STARTING", "图传正在启动中"),
    LIVE_ALREADY_RUNNING("LIVE_ALREADY_RUNNING", "图传已经处于运行状态"),
    LIVE_REQUEST_SEND_FAILED("LIVE_REQUEST_SEND_FAILED", "图传启动命令发送失败"),
    LIVE_START_REJECTED("LIVE_START_REJECTED", "设备拒绝启动图传"),
    LIVE_ACK_TIMEOUT("LIVE_ACK_TIMEOUT", "设备未在超时时间内确认图传启动"),
    TRTC_CREDENTIAL_FAILED("TRTC_CREDENTIAL_FAILED", "生成图传凭据失败"),
    INVALID_MESSAGE("INVALID_MESSAGE", "WebSocket 消息格式错误"),
    UNSUPPORTED_MESSAGE("UNSUPPORTED_MESSAGE", "暂不支持的 WebSocket 消息类型"),
    RATE_LIMITED("RATE_LIMITED", "请求过于频繁，请稍后重试"),
    ORDER_ALREADY_EXISTS("ORDER_ALREADY_EXISTS", "您已有待支付的订单，请先完成支付"),
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", "订单不存在"),
    ORDER_STATUS_INVALID("ORDER_STATUS_INVALID", "当前订单状态不允许此操作"),
    ROUTE_NOT_FOUND("ROUTE_NOT_FOUND", "未查询到航线信息，请先创建航线"),
    INTERNAL_ERROR("INTERNAL_ERROR", "系统内部错误"),
    MESSAGE_NOT_FOUND("MESSAGE_NOT_FOUND", "消息不存在"),
    SESSION_NOT_FOUND("SESSION_NOT_FOUND", "会话不存在"),
    REFUND_FAILED("REFUND_FAILED", "退款失败"),
    PAY_RECORD_NOT_FOUND("PAY_RECORD_NOT_FOUND", "支付记录不存在");

    private final String code;
    private final String defaultMessage;

    ApiErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

}
