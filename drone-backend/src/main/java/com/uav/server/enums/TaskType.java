package com.uav.server.enums;

import lombok.Getter;

@Getter
public enum TaskType {
    AERIAL_PHOTO("航拍"),
    TRANSPORT("调运");

    private final String description;

    TaskType(String description) {
        this.description = description;
    }
}