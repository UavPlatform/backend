package com.uav.server.enums;

import lombok.Getter;

@Getter
public enum TaskStatus {
    IDLE("空闲中"),
    IN_PROGRESS("执行中"),
    COMPLETED("执行完毕");

    private final String description;

    TaskStatus(String description) {
        this.description = description;
    }
}
