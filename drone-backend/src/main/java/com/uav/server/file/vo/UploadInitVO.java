package com.uav.server.file.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UploadInitVO {
    private String uploadId;
    private long chunkSize;
}
