package com.uav.server.file.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.file.config.FileStorageConfig;
import com.uav.server.file.entity.UploadedFile;
import com.uav.server.file.repository.FileRepository;
import com.uav.server.file.service.FileRecordService;
import com.uav.server.file.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class FileRecordServiceImpl implements FileRecordService {

    private final FileRepository fileRepository;
    private final FileStorageService fileStorageService;
    private final FileStorageConfig config;
    private final OrderRepository orderRepository;

    @Override
    public UploadedFile createInitRecord(String uploadId, String fileName, long fileSize,
                                         String mimeType, int totalChunks, Long userId) {
        UploadedFile entity = UploadedFile.builder()
                .uploadId(uploadId)
                .originalName(fileName)
                .fileSize(fileSize)
                .mimeType(mimeType)
                .fileSuffix(FileUtil.extName(fileName))
                .totalChunks(totalChunks)
                .userId(userId)
                .uploadStatus("INITIATED")
                .build();
        return fileRepository.save(entity);
    }

    @Override
    @Transactional
    public UploadedFile markCompleted(String uploadId, String storagePath, String fileUrl, long actualFileSize) {
        UploadedFile entity = fileRepository.findByUploadId(uploadId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.FILE_NOT_FOUND));
        entity.setUploadStatus("COMPLETED");
        entity.setStoragePath(storagePath);
        entity.setFileUrl(fileUrl);
        entity.setFileSize(actualFileSize);
        return fileRepository.save(entity);
    }

    @Override
    @Transactional
    public void markFailed(String uploadId) {
        fileRepository.findByUploadId(uploadId).ifPresent(entity -> {
            entity.setUploadStatus("FAILED");
            fileRepository.save(entity);
        });
    }

    @Override
    public UploadedFile getById(Long id) {
        return fileRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.FILE_NOT_FOUND));
    }

    @Override
    public Page<UploadedFile> listByUser(Long userId, int page, int size) {
        return fileRepository.findByUserIdOrderByCreateTimeDesc(userId, PageRequest.of(page, size));
    }

    @Override
    public Page<UploadedFile> listByOrder(String orderNum, int page, int size) {
        return fileRepository.findByOrderNumOrderByCreateTimeDesc(orderNum, PageRequest.of(page, size));
    }

    @Override
    public Page<UploadedFile> listByOrderAndUser(String orderNum, Long userId, int page, int size) {
        return fileRepository.findByOrderNumAndUserIdOrderByCreateTimeDesc(orderNum, userId, PageRequest.of(page, size));
    }

    @Override
    @Transactional
    public List<UploadedFile> bindToOrder(List<Long> fileIds, String orderNum, Long userId) {
        // 1. 校验所有文件属于当前用户且已完成上传
        List<UploadedFile> files = fileRepository.findByUserIdAndIdIn(userId, fileIds);
        if (files.size() != fileIds.size()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.FILE_NOT_FOUND,
                    "部分文件不存在或无权访问");
        }
        for (UploadedFile f : files) {
            if (!"COMPLETED".equals(f.getUploadStatus())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.FILE_UPLOAD_NOT_COMPLETE,
                        "文件 " + f.getOriginalName() + " 尚未完成上传");
            }
        }

        // 2. 校验订单属于当前用户
        MissionOrder order = orderRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND, "无权操作此订单");
        }

        // 3. 生成或获取 executeResult UUID
        String resultUuid = order.getExecuteResult();
        if (resultUuid == null || resultUuid.isEmpty()) {
            resultUuid = IdUtil.fastSimpleUUID();
            order.setExecuteResult(resultUuid);
            orderRepository.save(order);
        }

        // 4. 移动文件到订单目录并更新 DB
        String targetDir = "/order/result/" + resultUuid;

        for (UploadedFile f : files) {
            String oldPath = f.getStoragePath();
            String newPath = targetDir + "/" + f.getOriginalName();

            if (!newPath.equals(oldPath)) {
                String movedPath = fileStorageService.moveFile(oldPath, newPath);
                f.setStoragePath(movedPath);
                f.setFileUrl(fileStorageService.getFileUrl(movedPath));
            }
            f.setOrderNum(orderNum);
            fileRepository.save(f);
            log.info("文件绑定订单，fileId: {}, orderNum: {}, path: {}", f.getId(), orderNum, f.getStoragePath());
        }

        return files;
    }

    @Override
    @Transactional
    public void deleteRecord(Long id, Long userId) {
        UploadedFile entity = getById(id);
        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.FILE_ACCESS_DENIED);
        }
        try {
            fileStorageService.deleteFile(entity.getStoragePath());
        } catch (Exception e) {
            log.warn("删除 COS 文件失败，继续删除记录: {}", entity.getStoragePath(), e);
        }
        fileRepository.delete(entity);
        log.info("文件记录删除，fileId: {}, path: {}", id, entity.getStoragePath());
    }

    /**
     * 每小时清理过期的上传会话（标记为 FAILED）。
     */
    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void cleanupStaleUploads() {
        LocalDateTime deadline = LocalDateTime.now().minusHours(config.getUploadTtlHours());
        List<UploadedFile> stale = new ArrayList<>(fileRepository.findByUploadStatusAndCreateTimeBefore("INITIATED", deadline));
        stale.addAll(fileRepository.findByUploadStatusAndCreateTimeBefore("UPLOADING", deadline));

        for (UploadedFile f : stale) {
            f.setUploadStatus("FAILED");
            fileRepository.save(f);
        }
        if (!stale.isEmpty()) {
            log.info("清理过期上传会话 {} 条", stale.size());
        }
    }
}
