package com.uav.attachment.service;

import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * MinIO 存储访问封装（1B-9b，Q11=C MinIO 自建）。
 *
 * <p>连接配置全部环境注入：MINIO_ENDPOINT / MINIO_ACCESS_KEY / MINIO_SECRET_KEY / MINIO_BUCKET。
 * 四项均非空才算「已配置」；未配置时交付物接口返回 503（不抛出构建异常，保证上下文可启动）。
 * presigned URL 为本地签名计算，不产生对 MinIO 的网络调用。
 */
@Slf4j
@Service
public class MinioStorageService {

    /** presigned URL 有效期（秒） */
    public static final int PRESIGN_EXPIRY_SECONDS = (int) TimeUnit.MINUTES.toSeconds(15);

    private final MinioClient minioClient;
    private final String bucket;
    private final boolean configured;

    public MinioStorageService(
            @Value("${minio.endpoint:}") String endpoint,
            @Value("${minio.access-key:}") String accessKey,
            @Value("${minio.secret-key:}") String secretKey,
            @Value("${minio.bucket:}") String bucket,
            @Value("${minio.region:us-east-1}") String region) {
        this.bucket = bucket;
        this.configured = isNotBlank(endpoint) && isNotBlank(accessKey)
                && isNotBlank(secretKey) && isNotBlank(bucket);
        if (this.configured) {
            // 显式 region：避免 SDK 在签名前向服务端查询 bucket region（离线可算 presigned URL）
            this.minioClient = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .region(isNotBlank(region) ? region : "us-east-1")
                    .build();
            log.info("[MINIO] 对象存储已配置: endpoint={}, bucket={}", endpoint, bucket);
        } else {
            this.minioClient = null;
            log.warn("[MINIO] 对象存储未配置（MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET 为空），交付物接口将返回 503");
        }
    }

    public boolean isConfigured() {
        return configured;
    }

    private MinioClient requireClient() {
        if (!configured) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.INTERNAL_ERROR,
                    "对象存储未配置，请联系管理员");
        }
        return minioClient;
    }

    /** 生成上传用 presigned PUT URL。 */
    public String presignedPut(String objectKey, int expirySeconds) {
        try {
            return requireClient().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(expirySeconds, TimeUnit.SECONDS)
                    .build());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[MINIO] 生成上传 URL 失败: {}", e.getMessage(), e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                    "生成上传凭证失败");
        }
    }

    /** 生成下载用 presigned GET URL。 */
    public String presignedGet(String objectKey, int expirySeconds) {
        try {
            return requireClient().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(expirySeconds, TimeUnit.SECONDS)
                    .build());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[MINIO] 生成下载 URL 失败: {}", e.getMessage(), e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                    "生成下载凭证失败");
        }
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
