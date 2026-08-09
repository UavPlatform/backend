package com.uav.feed.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateFeedDto {
    @NotBlank(message = "文字内容不能为空")
    @Size(max = 2000, message = "内容长度不能超过2000字符")
    private String content;

    @Size(max = 9, message = "最多上传9个媒体文件")
    private List<Long> mediaIds;
}
