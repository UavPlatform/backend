package com.uav.upload.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "file.storage")
public class UploadStorageConfig {

    /** OSS 访问 URL 前缀，如 https://bucket.oss-cn-hangzhou.aliyuncs.com */
    private String ossPrefix;

    /** 单文件最大字节数，默认 500MB */
    private long maxFileSize = 536870912;

    /** 允许的文件扩展名，逗号分隔 */
    private String allowedExtensions = "jpg,jpeg,png,gif,webp,mp4,mov,avi,zip";

    /** 上传会话过期小时数 */
    private int uploadTtlHours = 24;

    /** OSS 上传完成回调 URL */
    private String callbackUrl;

    private Oss oss = new Oss();

    /** 拼接完整 OSS 访问 URL */
    public String buildFileUrl(String storagePath) {
        String prefix = ossPrefix;
        if (prefix == null || prefix.isEmpty()) {
            prefix = "https://" + oss.getBucket() + "." + oss.getEndpoint();
        }
        return prefix + storagePath;
    }

    @Getter
    @Setter
    public static class Oss {
        private String endpoint;
        private String bucket;
        private String accessKeyId;
        private String accessKeySecret;
    }
}
