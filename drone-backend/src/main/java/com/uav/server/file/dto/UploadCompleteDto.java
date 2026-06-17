package com.uav.server.file.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UploadCompleteDto {

    @NotBlank(message = "uploadId 不能为空")
    private String uploadId;
}
