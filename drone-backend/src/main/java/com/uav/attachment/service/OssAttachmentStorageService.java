package com.uav.attachment.service;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.upload.config.UploadStorageConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.Date;

@Slf4j
@Service
@ConditionalOnProperty(name = "attachment.storage.provider", havingValue = "oss")
public class OssAttachmentStorageService implements AttachmentObjectStorage {
    private final OSS client;
    private final UploadStorageConfig config;

    public OssAttachmentStorageService(OSS client, UploadStorageConfig config) {
        this.client = client;
        this.config = config;
        log.info("[ATTACHMENT OSS] Using existing OSS client: bucket={}", config.getOss().getBucket());
    }

    @Override
    public boolean isConfigured() {
        var oss = config.getOss();
        return client != null && nonBlank(oss.getEndpoint()) && nonBlank(oss.getBucket())
                && nonBlank(oss.getAccessKeyId()) && nonBlank(oss.getAccessKeySecret());
    }

    @Override
    public String presignedPut(String objectKey, int expirySeconds, String contentType) {
        return sign(objectKey, expirySeconds, HttpMethod.PUT, contentType);
    }

    @Override
    public String presignedGet(String objectKey, int expirySeconds) {
        return sign(objectKey, expirySeconds, HttpMethod.GET, null);
    }

    private String sign(String key, int seconds, HttpMethod method, String contentType) {
        requireConfigured();
        try {
            var request = new GeneratePresignedUrlRequest(config.getOss().getBucket(), key, method);
            request.setExpiration(new Date(System.currentTimeMillis() + seconds * 1000L));
            if (method == HttpMethod.PUT) request.setContentType(contentType);
            return client.generatePresignedUrl(request).toString();
        } catch (Exception error) {
            logFailure("presign", key, error);
            throw storageFailure();
        }
    }

    @Override
    public boolean isValidObject(String key, Long expectedSize) {
        requireConfigured();
        if (expectedSize == null || expectedSize <= 0) return false;
        try {
            return client.getObjectMetadata(config.getOss().getBucket(), key).getContentLength() == expectedSize;
        } catch (OSSException error) {
            if ("NoSuchKey".equals(error.getErrorCode())) return false;
            logFailure("verify", key, error);
            throw storageFailure();
        } catch (Exception error) {
            logFailure("verify", key, error);
            throw storageFailure();
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.INTERNAL_ERROR, "对象存储未配置，请联系管理员");
    }

    private BusinessException storageFailure() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.INTERNAL_ERROR,
                "OSS 对象存储操作失败，请稍后重试");
    }

    private void logFailure(String stage, String key, Exception error) {
        // SDK messages and request URLs may contain credentials or signatures.
        if (error instanceof OSSException ossError) {
            log.error("[ATTACHMENT OSS] stage={}, key={}, code={}, requestId={}",
                    stage, key, ossError.getErrorCode(), ossError.getRequestId());
        } else {
            log.error("[ATTACHMENT OSS] stage={}, key={}, exception={}", stage, key, error.getClass().getName());
        }
    }

    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
}
