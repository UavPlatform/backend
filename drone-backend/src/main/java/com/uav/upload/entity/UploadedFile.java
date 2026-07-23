package com.uav.upload.entity;

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

    /** 分片上传会话 ID（32 字符 UUID） */
    @Column(name = "upload_id", nullable = false, unique = true, length = 64)
    private String uploadId;

    /** 原始文件名 */
    @Column(name = "original_name", nullable = false, length = 256)
    private String originalName;

    /** 相对存储路径，如 /temp/{sessionId}/video.mp4 */
    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    /** 完整访问 URL */
    @Column(name = "file_url", length = 512)
    private String fileUrl;

    /** 文件大小（字节） */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** MIME 类型 */
    @Column(name = "mime_type", length = 128)
    private String mimeType;

    /** 扩展名（不含点） */
    @Column(name = "file_suffix", length = 16)
    private String fileSuffix;

    /** 上传状态：INITIATED / UPLOADING / COMPLETED / FAILED */
    @Column(name = "upload_status", nullable = false, length = 32)
    private String uploadStatus;

    /** 关联订单号，bind 后填充 */
    @Column(name = "order_num", length = 64)
    private String orderNum;

    /** 上传者用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 总分片数 */
    @Column(name = "total_chunks")
    private Integer totalChunks;

    /** 创建时间 */
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    /** 更新时间 */
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
        this.updateTime = this.createTime;
        if (this.uploadStatus == null) {
            this.uploadStatus = "INITIATED";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}
