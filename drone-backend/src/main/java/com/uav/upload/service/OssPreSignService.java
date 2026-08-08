package com.uav.upload.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.FileUploadStatus;
import com.uav.server.exception.BusinessException;
import com.uav.upload.config.UploadStorageConfig;
import com.uav.upload.entity.UploadedFile;
import com.uav.upload.repository.UploadRepository;
import com.uav.upload.vo.UploadSignVO;
import com.uav.upload.vo.UploadVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Date;

/**
 * OSS 预签名直传服务 —— 后端只签发上传凭证，客户端直传 OSS，后续添加审核，配置即可
 */
@Service
@ConditionalOnProperty(name = "file.storage.type", havingValue = "oss", matchIfMissing = true)
@Slf4j
public class OssPreSignService {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final OSS ossClient;
    private final UploadStorageConfig config;
    private final UploadRepository uploadRepository;

    @Autowired(required = false)
    private ContentModerationService moderationService;

    public OssPreSignService(UploadStorageConfig config, UploadRepository uploadRepository,
                             OSS ossClient) {
        this.config = config;
        this.uploadRepository = uploadRepository;
        this.ossClient = ossClient;
    }

    /**
     * 生成 OSS 预签名上传 URL，同时创建 DB 记录。
     */
    @Transactional
    public UploadSignVO generateUploadSign(String fileName, long fileSize, String mimeType, Long userId, String orderNum) {
        String suffix = FileUtil.extName(fileName);

        // 校验扩展名
        if (suffix == null || !config.getAllowedExtensions().toLowerCase().contains(suffix.toLowerCase())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.FILE_TYPE_NOT_ALLOWED,
                    "不支持的文件类型: " + suffix);
        }
        // 校验文件大小
        if (fileSize > config.getMaxFileSize()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.FILE_TOO_LARGE,
                    "文件大小超出限制: " + (config.getMaxFileSize() / 1024 / 1024) + "MB");
        }

        // 生成唯一对象 key
        String uploadId = IdUtil.fastSimpleUUID();
        String objectKey = "uploads/" + uploadId + "/" + fileName;

        // 创建 DB 记录
        UploadedFile entity = UploadedFile.builder()
                .uploadId(uploadId)
                .originalName(fileName)
                .fileSize(fileSize)
                .mimeType(mimeType != null ? mimeType : "application/octet-stream")
                .fileSuffix(suffix)
                .storagePath("/" + objectKey)
                .uploadStatus(FileUploadStatus.PENDING_SIGN)
                .userId(userId)
                .orderNum(orderNum)
                .build();
        UploadedFile saved = uploadRepository.save(entity);

        // 生成预签名 URL（默认 30 分钟有效）
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);
        Date expiresDate = Date.from(expiresAt.atZone(ZoneId.systemDefault()).toInstant());

        GeneratePresignedUrlRequest signRequest = new GeneratePresignedUrlRequest(
                config.getOss().getBucket(), objectKey, HttpMethod.PUT);
        signRequest.setExpiration(expiresDate);
        if (mimeType != null) {
            signRequest.setContentType(mimeType);
        }

        // OSS 上传完成回调
        if (config.getCallbackUrl() != null && !config.getCallbackUrl().isBlank()) {
            String callbackBody = "{\"bucket\":${bucket},\"object\":${object}," +
                    "\"size\":${size},\"mimeType\":${mimeType},\"fileId\":${x:fileId}}";
            String callback = "{\"callbackUrl\":\"" + config.getCallbackUrl()
                    + "\",\"callbackBody\":" + escapeJson(callbackBody)
                    + ",\"callbackBodyType\":\"application/json\"}";
            String callbackVar = "{\"x:fileId\":\"" + saved.getId() + "\"}";

            String callbackBase64 = Base64.getEncoder().encodeToString(
                    callback.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            signRequest.addHeader("x-oss-callback", callbackBase64);
            signRequest.addHeader("x-oss-callback-var", callbackVar);

            log.info("OSS callback 已绑定，fileId: {}, callbackUrl: {}", saved.getId(), config.getCallbackUrl());
        }

        URL preSignedUrl = ossClient.generatePresignedUrl(signRequest);

        log.info("OSS 预签名生成，fileId: {}, objectKey: {}, fileName: {}, size: {} bytes",
                saved.getId(), objectKey, fileName, fileSize);

        return new UploadSignVO(
                saved.getId(),
                preSignedUrl.toString(),
                objectKey,
                expiresAt.format(ISO_FMT)
        );
    }

    /**
     * 确认客户端直传完成，标记 DB 记录为 COMPLETED。
     */
    @Transactional
    public UploadVO confirmUpload(Long fileId, Long userId) {
        UploadedFile entity = uploadRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.FILE_NOT_FOUND));

        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.FILE_ACCESS_DENIED);
        }

        entity.setUploadStatus(FileUploadStatus.COMPLETED);
        entity.setFileUrl(config.buildFileUrl(entity.getStoragePath()));
        uploadRepository.save(entity);

        log.info("OSS 上传确认完成，fileId: {}, key: {}", fileId, entity.getStoragePath());
        return UploadVO.from(entity);
    }

    /**
     * 生成预签名下载 URL，校验文件所有权。
     */
    public String generateDownloadSign(Long fileId, Long userId) {
        UploadedFile entity = uploadRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.FILE_NOT_FOUND));

        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.FILE_ACCESS_DENIED);
        }

        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);
        Date expiresDate = Date.from(expiresAt.atZone(ZoneId.systemDefault()).toInstant());

        String objectKey = stripLeadingSlash(entity.getStoragePath());
        GeneratePresignedUrlRequest signRequest = new GeneratePresignedUrlRequest(
                config.getOss().getBucket(), objectKey, HttpMethod.GET);
        signRequest.setExpiration(expiresDate);

        URL preSignedUrl = ossClient.generatePresignedUrl(signRequest);
        log.info("OSS 下载预签名生成，fileId: {}, key: {}", fileId, objectKey);
        return preSignedUrl.toString();
    }

    /**
     * 处理 OSS 上传完成回调，标记文件完成并触发内容审核。
     */
    @Transactional
    public void handleCallback(long fileId, String object, long actualSize) {
        UploadedFile entity = uploadRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.FILE_NOT_FOUND));

        entity.setUploadStatus(FileUploadStatus.COMPLETED);
        entity.setFileSize(actualSize);
        entity.setStoragePath("/" + object);
        entity.setFileUrl(config.buildFileUrl("/" + object));
        uploadRepository.save(entity);

        log.info("OSS 回调确认完成，fileId: {}, object: {}, size: {} bytes", fileId, object, actualSize);

        // 内容审核（仅当 moderationService Bean 存在时执行）
        if (moderationService != null) {
            String suffix = entity.getFileSuffix();
            if (suffix != null) {
                String lower = suffix.toLowerCase();
                if (isImageType(lower)) {
                    boolean passed = moderationService.scanImage(object);
                    if (!passed) {
                        entity.setUploadStatus(FileUploadStatus.FAILED);
                        uploadRepository.save(entity);
                        log.warn("内容审核不通过，文件已标记失败，fileId: {}, object: {}", fileId, object);
                    }
                } else if (isVideoType(lower)) {
                    moderationService.scanVideoAsync(object);
                }
            }
        }
    }

    private static boolean isImageType(String suffix) {
        return switch (suffix) {
            case "jpg", "jpeg", "png", "gif", "webp", "bmp" -> true;
            default -> false;
        };
    }

    private static boolean isVideoType(String suffix) {
        return switch (suffix) {
            case "mp4", "mov", "avi", "mkv", "flv" -> true;
            default -> false;
        };
    }

    private static String escapeJson(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String stripLeadingSlash(String path) {
        if (path != null && path.startsWith("/")) {
            return path.substring(1);
        }
        return path;
    }

}
