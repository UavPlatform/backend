package com.uav.upload.service;

import java.io.InputStream;

/**
 * 文件存储服务接口 —— 当前仅 COS 实现，设计兼容本地存储。
 */
public interface UploadStorageService {

    /**
     * 初始化分片上传会话。
     *
     * @param fileName    原始文件名（用于拼 COS key）
     * @param totalChunks 总分片数
     * @return 上传会话 ID（UUID，后续 uploadChunk/complete 使用）
     */
    String initiateUpload(String fileName, int totalChunks);

    /**
     * 上传一个分片，幂等。
     *
     * @param uploadId   会话 ID
     * @param chunkIndex 分片索引（0-based）
     * @param chunkData  分片数据
     */
    void uploadChunk(String uploadId, int chunkIndex, byte[] chunkData);

    /**
     * 完成分片上传，合并所有分片。
     *
     * @param uploadId 会话 ID
     * @return 上传结果（相对路径 + 实际文件大小）
     */
    CompleteUploadResult completeUpload(String uploadId);

    /**
     * 移动文件到新路径（COS: copy + delete）。
     *
     * @param oldPath 旧相对路径
     * @param newPath 新相对路径
     * @return 新路径
     */
    String moveFile(String oldPath, String newPath);

    /**
     * 删除文件。
     *
     * @param storagePath 相对路径
     */
    void deleteFile(String storagePath);

    /**
     * 获取文件输入流（用于下载）。
     *
     * @param storagePath 相对路径
     * @return 文件流
     */
    InputStream getFileInputStream(String storagePath);

    /**
     * 获取文件访问 URL。
     *
     * @param storagePath 相对路径
     * @return 完整访问 URL
     */
    String getFileUrl(String storagePath);
}
