package com.uav.admin.pojo.vo;

import java.util.List;

/**
 * 1B-5 管理端分页信封（为批次 2 全局分页打样）：
 * content 为当前页数据，page 从 0 开始，totalElements/totalPages 供前端计算页码。
 */
public record AdminPageVo<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> AdminPageVo<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new AdminPageVo<>(content, page, size, totalElements, totalPages);
    }
}
