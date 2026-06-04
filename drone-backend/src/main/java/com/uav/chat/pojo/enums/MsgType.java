package com.uav.chat.pojo.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

public enum MsgType {

    CHAT(0, "聊天消息"),
    NOTICE(1, "系统通知"),
    ORDER(2, "订单状态"),
    COMMAND(3, "控制指令");

    private final int code;
    @Getter
    private final String desc;

    MsgType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @JsonValue  // 序列化时返回code（数字），前端也能用数字
    public int getCode() {
        return code;
    }

    @JsonCreator  // 反序列化时支持code或name
    public static MsgType fromCode(int code) {
        for (MsgType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown MsgType code: " + code);
    }

    // 可选：支持从字符串名称反序列化（大小写不敏感）
    public static MsgType fromName(String name) {
        for (MsgType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null; // 或抛异常
    }
}