package com.uav.server.file.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "file.storage")
public class FileStorageConfig {

    /** COS 访问 URL 前缀，如 https://bucket.cos.ap-guangzhou.myqcloud.com */
    private String cosPrefix;

    /** 单文件最大字节数，默认 500MB */
    private long maxFileSize = 536870912;

    /** 单个分片最大字节数，默认 5MB */
    private long maxChunkSize = 5242880;

    /** 允许的文件扩展名，逗号分隔 */
    private String allowedExtensions = "jpg,jpeg,png,gif,webp,mp4,mov,avi,zip";

    /** 上传会话过期小时数 */
    private int uploadTtlHours = 24;

    private Cos cos = new Cos();

    @Getter
    @Setter
    public static class Cos {
        private String region = "ap-guangzhou";
        private String bucket;
        private String secretId;
        private String secretKey;
    }
}
