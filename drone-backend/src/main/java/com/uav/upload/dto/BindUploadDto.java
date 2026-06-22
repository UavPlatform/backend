package com.uav.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BindUploadDto {

    @NotEmpty(message = "fileIds 不能为空")
    private List<Long> fileIds;

    @NotBlank(message = "orderNum 不能为空")
    private String orderNum;
}
