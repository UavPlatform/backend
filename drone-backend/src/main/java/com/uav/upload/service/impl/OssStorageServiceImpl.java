package com.uav.upload.service.impl;

import com.aliyun.oss.OSS;
import com.uav.upload.config.UploadStorageConfig;
import com.uav.upload.service.UploadStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "file.storage.type", havingValue = "oss", matchIfMissing = true)
@Slf4j
public class OssStorageServiceImpl implements UploadStorageService {

    private final OSS ossClient;
    private final UploadStorageConfig config;

    public OssStorageServiceImpl(UploadStorageConfig config, OSS ossClient) {
        this.config = config;
        this.ossClient = ossClient;
    }

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
    public String getFileUrl(String storagePath) {
        return config.buildFileUrl(storagePath);
    }

    private static String stripLeadingSlash(String path) {
        if (path != null && path.startsWith("/")) {
            return path.substring(1);
        }
        return path;
    }
}
