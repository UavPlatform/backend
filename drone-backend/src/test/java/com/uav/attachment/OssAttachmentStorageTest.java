package com.uav.attachment;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.uav.attachment.service.OssAttachmentStorageService;
import com.uav.server.exception.BusinessException;
import com.uav.upload.config.UploadStorageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OssAttachmentStorageTest {
    OSS client = mock(OSS.class);
    UploadStorageConfig config = new UploadStorageConfig();
    OssAttachmentStorageService storage;

    @BeforeEach void setup() {
        var settings = config.getOss();
        settings.setEndpoint("https://oss-cn-hangzhou.aliyuncs.com");
        settings.setBucket("test-bucket"); settings.setAccessKeyId("test-id"); settings.setAccessKeySecret("test-secret");
        storage = new OssAttachmentStorageService(client, config);
    }

    @Test void putSignatureUsesDeclaredMimeTypeAndExistingBucket() throws Exception {
        when(client.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(URI.create("https://storage.test/key").toURL());
        long before = System.currentTimeMillis();
        storage.presignedPut("key", 900, "image/jpeg");
        var request = ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(client).generatePresignedUrl(request.capture());
        assertThat(request.getValue().getMethod()).isEqualTo(HttpMethod.PUT);
        assertThat(request.getValue().getBucketName()).isEqualTo("test-bucket");
        assertThat(request.getValue().getContentType()).isEqualTo("image/jpeg");
        assertThat(request.getValue().getExpiration().getTime()).isBetween(before + 900000, System.currentTimeMillis() + 900000);
    }

    @Test void getSignatureDoesNotRequireUploadHeaders() throws Exception {
        when(client.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(URI.create("https://storage.test/key").toURL());
        storage.presignedGet("key", 900);
        var request = ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(client).generatePresignedUrl(request.capture());
        assertThat(request.getValue().getMethod()).isEqualTo(HttpMethod.GET);
        assertThat(request.getValue().getContentType()).isNull();
    }

    @Test void storedSizeMustMatchAndBePositive() {
        var metadata = new ObjectMetadata(); metadata.setContentLength(1024);
        when(client.getObjectMetadata("test-bucket", "key")).thenReturn(metadata);
        assertThat(storage.isValidObject("key", 1024L)).isTrue();
        assertThat(storage.isValidObject("key", 1023L)).isFalse();
        assertThat(storage.isValidObject("key", 0L)).isFalse();
    }

    @Test void missingKeyIsInvalid() {
        OSSException error = mock(OSSException.class); when(error.getErrorCode()).thenReturn("NoSuchKey");
        when(client.getObjectMetadata("test-bucket", "key")).thenThrow(error);
        assertThat(storage.isValidObject("key", 1024L)).isFalse();
    }

    @Test void deniedAccessFailsClosed() {
        OSSException error = mock(OSSException.class); when(error.getErrorCode()).thenReturn("AccessDenied");
        when(client.getObjectMetadata("test-bucket", "key")).thenThrow(error);
        assertThatThrownBy(() -> storage.isValidObject("key", 1024L)).isInstanceOf(BusinessException.class);
    }

    @Test void incompleteOssSettingsDoNotCountAsConfigured() {
        config.getOss().setAccessKeySecret("");
        assertThat(storage.isConfigured()).isFalse();
        assertThatThrownBy(() -> storage.presignedPut("key", 900, "image/jpeg")).isInstanceOf(BusinessException.class);
    }
}
