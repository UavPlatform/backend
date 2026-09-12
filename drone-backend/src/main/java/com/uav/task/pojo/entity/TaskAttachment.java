package com.uav.task.pojo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务交付物附件（1B-9b，t32 推荐的一对多方案 B）。
 * 一条任务可挂多个交付物文件；objectKey 为 MinIO 对象键（上传走 presigned PUT）。
 */
@Data
@Entity
@Table(name = "task_attachment", indexes = {
        @Index(name = "idx_task_attachment_task_num", columnList = "task_num"),
        @Index(name = "idx_task_attachment_object_key", columnList = "object_key", unique = true)
})
public class TaskAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务编号（业务键，与 task.task_num 对应） */
    @Column(name = "task_num", nullable = false, length = 64)
    private String taskNum;

    /** 上传者用户 id（= 任务的接单飞手） */
    @Column(name = "uploader_id", nullable = false)
    private Long uploaderId;

    /** MinIO 对象键（task-attachments/{taskNum}/{uuid}，全局唯一） */
    @Column(name = "object_key", nullable = false, length = 255, unique = true)
    private String objectKey;

    /** 原始文件名（展示用） */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** 内容类型（image/jpeg、video/mp4 等，白名单校验后写入） */
    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    /** 文件大小（字节） */
    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
    }
}
