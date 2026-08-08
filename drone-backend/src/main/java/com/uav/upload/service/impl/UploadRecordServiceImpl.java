package com.uav.upload.service.impl;

import cn.hutool.core.util.IdUtil;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.FileUploadStatus;
import com.uav.server.exception.BusinessException;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.upload.config.UploadStorageConfig;
import com.uav.upload.entity.UploadedFile;
import com.uav.upload.repository.UploadRepository;
import com.uav.upload.service.UploadRecordService;
import com.uav.upload.service.UploadStorageService;
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
public class UploadRecordServiceImpl implements UploadRecordService {

    private final UploadRepository uploadRepository;
    private final UploadStorageService uploadStorageService;
    private final UploadStorageConfig config;
    private final OrderRepository orderRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;

    @Override
    public UploadedFile getById(Long id) {
        return uploadRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.FILE_NOT_FOUND));
    }

    @Override
    public Page<UploadedFile> listByUser(Long userId, int page, int size) {
        return uploadRepository.findByUserIdOrderByCreateTimeDesc(userId, PageRequest.of(page, size));
    }

    @Override
    public Page<UploadedFile> listByOrder(String orderNum, int page, int size) {
        return uploadRepository.findByOrderNumOrderByCreateTimeDesc(orderNum, PageRequest.of(page, size));
    }

    @Override
    public Page<UploadedFile> listByOrderAndUser(String orderNum, Long userId, int page, int size) {
        return uploadRepository.findByOrderNumAndUserIdOrderByCreateTimeDesc(orderNum, userId, PageRequest.of(page, size));
    }

    @Override
    @Transactional
    public List<UploadedFile> bindToOrder(List<Long> fileIds, String orderNum, Long userId, Integer role) {
        List<UploadedFile> files = validateFilesOwnership(fileIds, userId);
        MissionOrder order = orderRepository.findByOrderNum(orderNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));

        validateDeliveryPermission(order, userId, role);

        String targetDir = ensureResultDir(order);
        moveFilesToOrderDir(files, targetDir, orderNum);

        return files;
    }

    private List<UploadedFile> validateFilesOwnership(List<Long> fileIds, Long userId) {
        List<UploadedFile> files = uploadRepository.findByUserIdAndIdIn(userId, fileIds);
        if (files.size() != fileIds.size()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.FILE_NOT_FOUND,
                    "部分文件不存在或无权访问");
        }
        for (UploadedFile f : files) {
            if (f.getUploadStatus() != FileUploadStatus.COMPLETED) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.FILE_UPLOAD_NOT_COMPLETE,
                        "文件 " + f.getOriginalName() + " 尚未完成上传");
            }
        }
        return files;
    }

    private void validateDeliveryPermission(MissionOrder order, Long userId, Integer role) {
        boolean isAdmin = role != null && role >= 1;
        boolean isRider = order.getTask() != null
                && taskAssignmentRepository.findByTaskId(order.getTask().getId())
                        .map(a -> a.getRiderId().equals(userId)).orElse(false);
        if (!isRider && !isAdmin) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ORDER_NOT_FOUND,
                    "仅飞手或管理员可交付文件");
        }
    }

    private String ensureResultDir(MissionOrder order) {
        String resultUuid = order.getExecuteResult();
        if (resultUuid == null || resultUuid.isEmpty()) {
            resultUuid = IdUtil.fastSimpleUUID();
            order.setExecuteResult(resultUuid);
            orderRepository.save(order);
        }
        return "/order/result/" + resultUuid;
    }

    private void moveFilesToOrderDir(List<UploadedFile> files, String targetDir, String orderNum) {
        for (UploadedFile f : files) {
            String oldPath = f.getStoragePath();
            String newPath = targetDir + "/" + f.getOriginalName();

            if (!newPath.equals(oldPath)) {
                String movedPath = uploadStorageService.moveFile(oldPath, newPath);
                f.setStoragePath(movedPath);
                f.setFileUrl(uploadStorageService.getFileUrl(movedPath));
            }
            f.setOrderNum(orderNum);
            uploadRepository.save(f);
            log.info("文件绑定订单，fileId: {}, orderNum: {}, path: {}", f.getId(), orderNum, f.getStoragePath());
        }
    }

    @Override
    @Transactional
    public void deleteRecord(Long id, Long userId) {
        UploadedFile entity = getById(id);
        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.FILE_ACCESS_DENIED);
        }
        try {
            uploadStorageService.deleteFile(entity.getStoragePath());
        } catch (Exception e) {
            log.warn("删除 OSS 文件失败，继续删除记录: {}", entity.getStoragePath(), e);
        }
        uploadRepository.delete(entity);
        log.info("文件记录删除，fileId: {}, path: {}", id, entity.getStoragePath());
    }

    @Scheduled(fixedRate = 3600000)
    @Transactional
    public void cleanupStaleUploads() {
        LocalDateTime deadline = LocalDateTime.now().minusHours(config.getUploadTtlHours());
        List<UploadedFile> stale = new ArrayList<>(
                uploadRepository.findByUploadStatusAndCreateTimeBefore(FileUploadStatus.PENDING_SIGN, deadline));

        for (UploadedFile f : stale) {
            try {
                uploadStorageService.deleteFile(f.getStoragePath());
            } catch (Exception e) {
                log.warn("清理过期 OSS 文件失败: {}", f.getStoragePath(), e);
            }
            f.setUploadStatus(FileUploadStatus.FAILED);
            uploadRepository.save(f);
        }
        if (!stale.isEmpty()) {
            log.info("清理过期上传会话 {} 条（含 OSS 文件）", stale.size());
        }
    }
}
