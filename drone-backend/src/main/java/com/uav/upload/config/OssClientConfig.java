package com.uav.upload.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OSS 客户端 Bean 配置，type=oss 时才加载。
 */
@Configuration
@ConditionalOnProperty(name = "file.storage.type", havingValue = "oss")
@Slf4j
public class OssClientConfig {

    @Bean
    public OSS ossClient(UploadStorageConfig config) {
        UploadStorageConfig.Oss oss = config.getOss();
        log.info("OSS client Bean 创建，endpoint: {}, bucket: {}", oss.getEndpoint(), oss.getBucket());
        return new OSSClientBuilder().build(oss.getEndpoint(), oss.getAccessKeyId(), oss.getAccessKeySecret());
    }
}
