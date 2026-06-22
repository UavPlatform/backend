package com.uav.upload.vo;

import com.uav.upload.entity.UploadedFile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class UploadVO {
    private Long id;
    private String uploadId;
    private String originalName;
    private String storagePath;
    private String fileUrl;
    private Long fileSize;
    private String mimeType;
    private String fileSuffix;
    private String uploadStatus;
    private String orderNum;
    private Long userId;
    private Integer totalChunks;
    private LocalDateTime createTime;

    public static UploadVO from(UploadedFile entity) {
        return UploadVO.builder()
                .id(entity.getId())
                .uploadId(entity.getUploadId())
                .originalName(entity.getOriginalName())
                .storagePath(entity.getStoragePath())
                .fileUrl(entity.getFileUrl())
                .fileSize(entity.getFileSize())
                .mimeType(entity.getMimeType())
                .fileSuffix(entity.getFileSuffix())
                .uploadStatus(entity.getUploadStatus())
                .orderNum(entity.getOrderNum())
                .userId(entity.getUserId())
                .totalChunks(entity.getTotalChunks())
                .createTime(entity.getCreateTime())
                .build();
    }
}
