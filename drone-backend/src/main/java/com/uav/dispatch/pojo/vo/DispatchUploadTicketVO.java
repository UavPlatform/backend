package com.uav.dispatch.pojo.vo;

public record DispatchUploadTicketVO(String materialId, String uploadUrl, String method,
        String contentType, long sizeBytes, int expireSeconds) { }
