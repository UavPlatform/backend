package com.uav.server.file.service.impl;

import cn.hutool.core.util.IdUtil;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.model.*;
import com.qcloud.cos.region.Region;
import com.uav.server.file.config.FileStorageConfig;
import com.uav.server.file.service.CompleteUploadResult;
import com.uav.server.file.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 腾讯云 COS 文件存储实现 —— 分片上传。
 */
@Service
@Slf4j
public class CosFileStorageServiceImpl implements FileStorageService {

    private final COSClient cosClient;
    private final FileStorageConfig config;

    /** 上传会话：key=会话ID, value=COS 分片上传上下文 */
    private final Map<String, CosUploadContext> sessions = new ConcurrentHashMap<>();

    public CosFileStorageServiceImpl(FileStorageConfig config) {
        this.config = config;
        FileStorageConfig.Cos cos = config.getCos();
        COSCredentials cred = new BasicCOSCredentials(cos.getSecretId(), cos.getSecretKey());
        ClientConfig clientConfig = new ClientConfig(new Region(cos.getRegion()));
        this.cosClient = new COSClient(cred, clientConfig);
        log.info("COS client 初始化完成，bucket: {}, region: {}", cos.getBucket(), cos.getRegion());
    }

    @Override
    public String initiateUpload(String fileName, int totalChunks) {
        String sessionId = IdUtil.fastSimpleUUID();
        String key = "temp/" + sessionId + "/" + fileName;

        InitiateMultipartUploadRequest request =
                new InitiateMultipartUploadRequest(config.getCos().getBucket(), key);
        InitiateMultipartUploadResult result = cosClient.initiateMultipartUpload(request);

        CosUploadContext ctx = new CosUploadContext();
        ctx.cosUploadId = result.getUploadId();
        ctx.key = key;
        ctx.fileName = fileName;
        ctx.totalChunks = totalChunks;
        ctx.partEtags = new CopyOnWriteArrayList<>();
        ctx.partSizes = new CopyOnWriteArrayList<>();
        sessions.put(sessionId, ctx);

        log.info("COS 分片上传初始化，sessionId: {}, cosUploadId: {}, key: {}, totalChunks: {}",
                sessionId, ctx.cosUploadId, key, totalChunks);
        return sessionId;
    }

    @Override
    public void uploadChunk(String uploadId, int chunkIndex, byte[] chunkData) {
        CosUploadContext ctx = sessions.get(uploadId);
        if (ctx == null) {
            // 幂等：若会话已过期但 complete 被调用过，忽略
            log.warn("上传会话不存在或已过期，sessionId: {}", uploadId);
            return;
        }

        int partNumber = chunkIndex + 1;
        UploadPartRequest request = new UploadPartRequest()
                .withBucketName(config.getCos().getBucket())
                .withKey(ctx.key)
                .withUploadId(ctx.cosUploadId)
                .withPartNumber(partNumber)
                .withInputStream(new ByteArrayInputStream(chunkData))
                .withPartSize(chunkData.length);

        UploadPartResult result = cosClient.uploadPart(request);
        PartETag etag = result.getPartETag();
        // 确保列表容量足够（CopyOnWriteArrayList 需要预先填充）
        while (ctx.partEtags.size() <= chunkIndex) {
            ctx.partEtags.add(null);
        }
        ctx.partEtags.set(chunkIndex, etag);

        // 记录分片大小
        while (ctx.partSizes.size() <= chunkIndex) {
            ctx.partSizes.add(0L);
        }
        ctx.partSizes.set(chunkIndex, (long) chunkData.length);
    }

    @Override
    public CompleteUploadResult completeUpload(String uploadId) {
        CosUploadContext ctx = sessions.remove(uploadId);
        if (ctx == null) {
            throw new IllegalStateException("上传会话不存在或已过期: " + uploadId);
        }

        List<PartETag> etags = ctx.partEtags;
        // 检查是否有缺失分片
        for (int i = 0; i < etags.size(); i++) {
            if (etags.get(i) == null) {
                // 取消 COS 上传
                cosClient.abortMultipartUpload(new AbortMultipartUploadRequest(
                        config.getCos().getBucket(), ctx.key, ctx.cosUploadId));
                throw new IllegalArgumentException("分片缺失: index=" + i + ", uploadId=" + uploadId);
            }
        }

        CompleteMultipartUploadRequest request = new CompleteMultipartUploadRequest(
                config.getCos().getBucket(), ctx.key, ctx.cosUploadId, etags);
        cosClient.completeMultipartUpload(request);

        // 获取实际文件大小（由各分片累加）
        long actualSize = ctx.partSizes.stream().mapToLong(Long::longValue).sum();
        String storagePath = "/" + ctx.key; // 相对路径以 / 开头

        log.info("COS 分片上传完成，sessionId: {}, key: {}, size: {} bytes", uploadId, ctx.key, actualSize);
        return new CompleteUploadResult(storagePath, actualSize);
    }

    @Override
    public String moveFile(String oldPath, String newPath) {
        String bucket = config.getCos().getBucket();
        String srcKey = stripLeadingSlash(oldPath);
        String destKey = stripLeadingSlash(newPath);

        // COS 没有原生 rename，先 copy 再 delete
        cosClient.copyObject(bucket, srcKey, bucket, destKey);
        cosClient.deleteObject(bucket, srcKey);

        String resultPath = "/" + destKey;
        log.info("COS 文件移动: {} -> {}", srcKey, destKey);
        return resultPath;
    }

    @Override
    public void deleteFile(String storagePath) {
        String key = stripLeadingSlash(storagePath);
        cosClient.deleteObject(config.getCos().getBucket(), key);
        log.info("COS 文件删除: {}", key);
    }

    @Override
    public InputStream getFileInputStream(String storagePath) {
        String key = stripLeadingSlash(storagePath);
        GetObjectRequest request = new GetObjectRequest(config.getCos().getBucket(), key);
        COSObject cosObject = cosClient.getObject(request);
        return cosObject.getObjectContent();
    }

    @Override
    public String getFileUrl(String storagePath) {
        String prefix = config.getCosPrefix();
        if (prefix == null || prefix.isEmpty()) {
            // fallback: 拼接默认 COS 域名
            prefix = "https://" + config.getCos().getBucket() + ".cos."
                    + config.getCos().getRegion() + ".myqcloud.com";
        }
        return prefix + storagePath;
    }

    // ---- helpers ----

    private static String stripLeadingSlash(String path) {
        if (path != null && path.startsWith("/")) {
            return path.substring(1);
        }
        return path;
    }

    /**
     * COS 分片上传上下文。
     */
    private static class CosUploadContext {
        String cosUploadId;
        String key;
        String fileName;
        int totalChunks;
        List<PartETag> partEtags;
        List<Long> partSizes;
    }
}
