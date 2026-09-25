package com.uav.admin.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 1B-5 管理端分页信封（为批次 2 全局分页打样）：
 * content 为当前页数据，page 从 0 开始，totalElements/totalPages 供前端计算页码。
 */
@Schema(description = "管理端分页信封（page 从 0 开始）")
public record AdminPageVo<T>(
        @Schema(description = "当前页数据列表")
        List<T> content,
        @Schema(description = "当前页码（从 0 开始）")
        int page,
        @Schema(description = "每页条数")
        int size,
        @Schema(description = "总记录数")
        long totalElements,
        @Schema(description = "总页数（由总记录数与每页条数计算）")
        int totalPages) {

    public static <T> AdminPageVo<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new AdminPageVo<>(content, page, size, totalElements, totalPages);
    }
}
