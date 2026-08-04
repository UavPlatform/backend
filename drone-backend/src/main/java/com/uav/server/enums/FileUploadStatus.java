package com.uav.server.enums;

import lombok.Getter;

@Getter
public enum FileUploadStatus {
    PENDING_SIGN(0, "待上传"),
    COMPLETED(1, "已完成"),
    FAILED(2, "已失败");

    private final int code;
    private final String desc;

    FileUploadStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
