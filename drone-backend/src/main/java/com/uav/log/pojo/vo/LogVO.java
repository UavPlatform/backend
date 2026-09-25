package com.uav.log.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "日志内容")
public record LogVO(
        @Schema(description = "日志行列表（按时间顺序）")
        List<String> logs) {}
