package com.uav.upload.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import com.uav.upload.config.UploadStorageConfig;
import com.uav.upload.service.CompleteUploadResult;
import com.uav.upload.service.UploadStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 阿里云 OSS 存储实现 —— 仅提供 move/delete/download/url 操作，
 * 上传走预签名直传，不经过后端。
 */
@Service
@ConditionalOnProperty(name = "file.storage.type", havingValue = "oss")
@Slf4j
public class OssStorageServiceImpl implements UploadStorageService {

    private final OSS ossClient;
    private final UploadStorageConfig config;

    public OssStorageServiceImpl(UploadStorageConfig config, OSS ossClient) {
        this.config = config;
        this.ossClient = ossClient;
    }

    // ---- 分片方法不被预签名模式使用 ----

    @Override
    public String initiateUpload(String fileName, int totalChunks) {
        throw new UnsupportedOperationException("OSS 预签名模式不支持分片上传 API，请使用 /upload/sign");
    }

    @Override
    public void uploadChunk(String uploadId, int chunkIndex, byte[] chunkData) {
        throw new UnsupportedOperationException("OSS 预签名模式不支持分片上传 API");
    }

    @Override
    public CompleteUploadResult completeUpload(String uploadId) {
        throw new UnsupportedOperationException("OSS 预签名模式不支持分片上传 API");
    }

    // ---- 通用文件操作 ----

    @Override
    public String moveFile(String oldPath, String newPath) {
        String bucket = config.getOss().getBucket();
        String srcKey = stripLeadingSlash(oldPath);
        String destKey = stripLeadingSlash(newPath);

        ossClient.copyObject(bucket, srcKey, bucket, destKey);
        ossClient.deleteObject(bucket, srcKey);

        String resultPath = "/" + destKey;
        log.info("OSS 文件移动: {} -> {}", srcKey, destKey);
        return resultPath;
    }

    @Override
    public void deleteFile(String storagePath) {
        String key = stripLeadingSlash(storagePath);
        ossClient.deleteObject(config.getOss().getBucket(), key);
        log.info("OSS 文件删除: {}", key);
    }

    @Override
    public InputStream getFileInputStream(String storagePath) {
        String key = stripLeadingSlash(storagePath);
        OSSObject ossObject = ossClient.getObject(config.getOss().getBucket(), key);
        return ossObject.getObjectContent();
    }

    @Override
    public String getFileUrl(String storagePath) {
        String prefix = config.getOssPrefix();
        if (prefix == null || prefix.isEmpty()) {
            prefix = "https://" + config.getOss().getBucket() + "." + config.getOss().getEndpoint();
        }
        return prefix + storagePath;
    }

    private static String stripLeadingSlash(String path) {
        if (path != null && path.startsWith("/")) {
            return path.substring(1);
        }
        return path;
    }
}
