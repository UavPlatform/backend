package com.uav.upload.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UploadInitDto {

    @NotBlank(message = "文件名不能为空")
    private String fileName;

    /** 文件总大小（字节） */
    @Min(value = 1, message = "文件大小必须大于 0")
    private long fileSize;

    /** MIME 类型，如 image/jpeg、video/mp4 */
    private String mimeType;

    @Min(value = 1, message = "总分片数必须大于 0")
    private int totalChunks;
}
