package com.uav.dispatch.pojo.vo;
import java.time.LocalDateTime;

public record DispatchMaterialVO(String materialId, String purpose, String fileName, String contentType,
        long sizeBytes, String downloadUrl, String uploadStatus, Long uploaderId, LocalDateTime confirmedAt) { }
