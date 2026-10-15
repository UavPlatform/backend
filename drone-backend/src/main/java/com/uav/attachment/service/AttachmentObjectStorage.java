package com.uav.attachment.service;

/** Attachment storage boundary (REQ-BACKEND-001; TASK-PLATFORM-002), shared by MinIO and OSS. */
public interface AttachmentObjectStorage {
    int PRESIGN_EXPIRY_SECONDS = 15 * 60;
    boolean isConfigured();
    String presignedPut(String objectKey, int expirySeconds, String contentType);
    String presignedGet(String objectKey, int expirySeconds);
    boolean isValidObject(String objectKey, Long expectedSize);
}
