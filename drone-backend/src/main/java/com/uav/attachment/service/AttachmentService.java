package com.uav.attachment.service;

import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.mapper.TaskAttachmentRepository;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskAttachment;
import com.uav.task.pojo.entity.TaskAssignment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 交付物附件业务（1B-9b）：授权、类型/大小限制、presigned URL、附件列表。
 *
 * <p>授权口径（裁决 Q8=B）：上传=任务的接单飞手（task_assignment.rider_id）；
 * 查看/下载=任务所有者与接单飞手。objectKey 全局唯一（task-attachments/{taskNum}/{uuid}）。
 */
@Slf4j
@Service
public class AttachmentService {

    /** 允许的交付物类型与大小上限 */
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> ALLOWED_VIDEO_TYPES = Set.of("video/mp4", "video/quicktime");
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;   // 20 MiB
    private static final long MAX_VIDEO_BYTES = 500L * 1024 * 1024;  // 500 MiB
    private static final int MAX_NOTE_LENGTH = 255;

    private final TaskRepository taskRepository;

    private final TaskAttachmentRepository taskAttachmentRepository;

    private final com.uav.task.mapper.TaskAssignmentRepository taskAssignmentRepository;

    private final MinioStorageService minioStorageService;

    public AttachmentService(TaskRepository taskRepository,
                             TaskAttachmentRepository taskAttachmentRepository,
                             com.uav.task.mapper.TaskAssignmentRepository taskAssignmentRepository,
                             MinioStorageService minioStorageService) {
        this.taskRepository = taskRepository;
        this.taskAttachmentRepository = taskAttachmentRepository;
        this.taskAssignmentRepository = taskAssignmentRepository;
        this.minioStorageService = minioStorageService;
    }

    /**
     * 请求上传凭证（t47 授权放宽）：任务所有者或接单飞手均可上传（用户补充上传属既定范围）。
     * 校验授权 + 类型/大小限制，登记附件行并返回 presigned PUT。
     */
    @Transactional
    public Map<String, Object> requestUploadUrl(Long callerId, String taskNum, String fileName,
                                                String contentType, Long sizeBytes) {
        requireConfigured();
        Task task = requireTask(taskNum);
        requireOwnerOrRider(task, callerId);
        validateFile(fileName, contentType, sizeBytes);

        String objectKey = "task-attachments/" + taskNum + "/" + UUID.randomUUID().toString()
                .replace("-", "");
        TaskAttachment attachment = new TaskAttachment();
        attachment.setTaskNum(taskNum);
        attachment.setUploaderId(callerId);
        attachment.setObjectKey(objectKey);
        attachment.setFileName(fileName.trim());
        attachment.setContentType(contentType);
        attachment.setSizeBytes(sizeBytes);
        taskAttachmentRepository.save(attachment);

        String uploadUrl = minioStorageService.presignedPut(objectKey,
                com.uav.attachment.service.MinioStorageService.PRESIGN_EXPIRY_SECONDS);
        log.info("交付物上传凭证已签发: taskNum={}, uploader={}, objectKey={}, fileName={}, size={}",
                taskNum, callerId, objectKey, fileName, sizeBytes);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objectKey", objectKey);
        result.put("uploadUrl", uploadUrl);
        result.put("expireSeconds", com.uav.attachment.service.MinioStorageService.PRESIGN_EXPIRY_SECONDS);
        result.put("method", "PUT");
        return result;
    }

    /**
     * 附件列表（任务所有者与接单飞手可看），逐条附 presigned 下载 URL。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAttachments(Long callerId, String taskNum) {
        requireConfigured();
        Task task = requireTask(taskNum);
        requireOwnerOrRider(task, callerId);

        return taskAttachmentRepository.findByTaskNumOrderByCreateTimeAsc(taskNum).stream()
                .map(a -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("objectKey", a.getObjectKey());
                    item.put("fileName", a.getFileName());
                    item.put("contentType", a.getContentType());
                    item.put("sizeBytes", a.getSizeBytes());
                    item.put("uploaderId", a.getUploaderId());
                    item.put("createTime", a.getCreateTime());
                    item.put("downloadUrl", minioStorageService.presignedGet(a.getObjectKey(),
                            com.uav.attachment.service.MinioStorageService.PRESIGN_EXPIRY_SECONDS));
                    return item;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 单个附件的下载凭证（所有者或飞手）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> downloadUrl(Long callerId, String taskNum, String objectKey) {
        requireConfigured();
        Task task = requireTask(taskNum);
        requireOwnerOrRider(task, callerId);
        TaskAttachment attachment = taskAttachmentRepository
                .findByTaskNumAndObjectKey(taskNum, objectKey)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM,
                        "附件不存在"));
        String downloadUrl = minioStorageService.presignedGet(attachment.getObjectKey(),
                com.uav.attachment.service.MinioStorageService.PRESIGN_EXPIRY_SECONDS);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objectKey", attachment.getObjectKey());
        result.put("fileName", attachment.getFileName());
        result.put("contentType", attachment.getContentType());
        result.put("sizeBytes", attachment.getSizeBytes());
        result.put("downloadUrl", downloadUrl);
        result.put("expireSeconds", com.uav.attachment.service.MinioStorageService.PRESIGN_EXPIRY_SECONDS);
        return result;
    }

    // ---------- 校验 ----------

    private void requireConfigured() {
        if (!minioStorageService.isConfigured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.INTERNAL_ERROR,
                    "对象存储未配置，请联系管理员");
        }
    }

    private Task requireTask(String taskNum) {
        return taskRepository.findByTaskNum(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));
    }

    private void requireOwnerOrRider(Task task, Long callerId) {
        boolean isOwner = task.getUserId().equals(callerId);
        boolean isRider = taskAssignmentRepository.findByTaskId(task.getId())
                .map(a -> a.getRiderId().equals(callerId))
                .orElse(false);
        if (!isOwner && !isRider) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION, "无权查看该任务交付物");
        }
    }

    private void validateFile(String fileName, String contentType, Long sizeBytes) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "文件名不能为空");
        }
        if (fileName.length() > MAX_NOTE_LENGTH) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "文件名过长");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "内容类型不能为空");
        }
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "文件大小非法");
        }
        boolean isImage = ALLOWED_IMAGE_TYPES.contains(contentType);
        boolean isVideo = ALLOWED_VIDEO_TYPES.contains(contentType);
        if (!isImage && !isVideo) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "仅支持图片（jpeg/png/webp）与视频（mp4/mov）类型");
        }
        long max = isImage ? MAX_IMAGE_BYTES : MAX_VIDEO_BYTES;
        if (sizeBytes > max) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    (isImage ? "图片" : "视频") + "大小超出限制（上限 " + (max / 1024 / 1024) + " MB）");
        }
    }
}
