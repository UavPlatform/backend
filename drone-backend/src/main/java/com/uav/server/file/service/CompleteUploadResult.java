package com.uav.server.file.service;

/**
 * 分片上传完成结果。
 *
 * @param storagePath   相对存储路径，如 /temp/{uploadId}/video.mp4
 * @param actualFileSize 实际文件字节数
 */
public record CompleteUploadResult(String storagePath, long actualFileSize) {
}
