package com.uav.upload.entity;

import com.uav.server.enums.FileUploadStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Entity
@Table(name = "uploaded_file", indexes = {
        @Index(name = "idx_upload_id", columnList = "upload_id", unique = true),
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_order_num", columnList = "order_num")
})
public class UploadedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upload_id", nullable = false, unique = true, length = 64)
    private String uploadId;

    @Column(name = "original_name", nullable = false, length = 256)
    private String originalName;

    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    @Column(name = "file_url", length = 512)
    private String fileUrl;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "file_suffix", length = 16)
    private String fileSuffix;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_status", nullable = false, length = 32)
    private FileUploadStatus uploadStatus;

    @Column(name = "order_num", length = 64)
    private String orderNum;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
        this.updateTime = this.createTime;
        if (this.uploadStatus == null) {
            this.uploadStatus = FileUploadStatus.PENDING_SIGN;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}
