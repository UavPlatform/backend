package com.uav.upload.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.upload.config.UploadStorageConfig;
import com.uav.upload.entity.UploadedFile;
import com.uav.upload.repository.UploadRepository;
import com.uav.upload.vo.UploadSignVO;
import com.uav.upload.vo.UploadVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * OSS 预签名直传服务 —— 后端只签发上传凭证，客户端直传 OSS。
 */
@Service
@ConditionalOnProperty(name = "file.storage.type", havingValue = "oss")
@Slf4j
public class OssPreSignService {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final OSS ossClient;
    private final UploadStorageConfig config;
    private final UploadRepository uploadRepository;

    public OssPreSignService(UploadStorageConfig config, UploadRepository uploadRepository, OSS ossClient) {
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
                .uploadStatus("PENDING_SIGN")
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

        entity.setUploadStatus("COMPLETED");
        entity.setFileUrl(getFullUrl(entity.getStoragePath()));
        uploadRepository.save(entity);

        log.info("OSS 上传确认完成，fileId: {}, key: {}", fileId, entity.getStoragePath());
        return UploadVO.from(entity);
    }

    private String getFullUrl(String storagePath) {
        String prefix = config.getOssPrefix();
        if (prefix == null || prefix.isEmpty()) {
            prefix = "https://" + config.getOss().getBucket() + "." + config.getOss().getEndpoint();
        }
        return prefix + storagePath;
    }
}
