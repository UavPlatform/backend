package com.uav.upload.service;

/**
 * 文件存储服务接口 —— OSS 实现。
 */
public interface UploadStorageService {

    /**
     * 移动文件到新路径（copy + delete）。
     */
    String moveFile(String oldPath, String newPath);

    /**
     * 删除文件。
     */
    void deleteFile(String storagePath);

    /**
     * 获取文件访问 URL。
     */
    String getFileUrl(String storagePath);
}
