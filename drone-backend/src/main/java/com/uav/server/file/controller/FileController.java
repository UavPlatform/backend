package com.uav.server.file.controller;

import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.file.config.FileStorageConfig;
import com.uav.server.file.dto.BindFileDto;
import com.uav.server.file.dto.UploadCompleteDto;
import com.uav.server.file.dto.UploadInitDto;
import com.uav.server.file.entity.UploadedFile;
import com.uav.server.file.service.CompleteUploadResult;
import com.uav.server.file.service.FileRecordService;
import com.uav.server.file.service.FileStorageService;
import com.uav.server.file.vo.FileVO;
import com.uav.server.file.vo.UploadInitVO;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;

@Tag(name = "File API", description = "文件上传管理")
@RestController
@RequestMapping("/file")
@Slf4j
public class FileController {

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private FileRecordService fileRecordService;

    @Autowired
    private FileStorageConfig config;

    @OperationLog("发起分片上传")
    @Operation(summary = "发起分片上传")
    @RateLimiter(limit = 10, windowSeconds = 60)
    @PostMapping("/initiate")
    public Result<UploadInitVO> initiate(@RequestBody @Valid UploadInitDto dto) {
        Long userId = UserContext.getUserId();

        // 校验文件扩展名
        String ext = dto.getFileName().substring(dto.getFileName().lastIndexOf('.') + 1).toLowerCase();
        Set<String> allowed = new HashSet<>(Arrays.asList(config.getAllowedExtensions().split(",")));
        if (!allowed.contains(ext)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.FILE_TYPE_NOT_ALLOWED,
                    "不支持的文件类型: " + ext);
        }

        // 校验文件大小
        if (dto.getFileSize() > config.getMaxFileSize()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.FILE_TOO_LARGE,
                    "文件大小超出限制: " + (config.getMaxFileSize() / 1024 / 1024) + "MB");
        }

        String uploadId = fileStorageService.initiateUpload(
                dto.getFileName(), dto.getTotalChunks());

        fileRecordService.createInitRecord(uploadId, dto.getFileName(), dto.getFileSize(),
                dto.getMimeType(), dto.getTotalChunks(), userId);

        UploadInitVO vo = new UploadInitVO(uploadId, config.getMaxChunkSize());
        return Result.success("上传已初始化", vo);
    }

    @OperationLog("上传分片")
    @Operation(summary = "上传分片")
    @RateLimiter(limit = 100, windowSeconds = 60)
    @PostMapping("/upload-chunk")
    public Result<Map<String, Object>> uploadChunk(@RequestParam String uploadId,
                                                   @RequestParam int chunkIndex,
                                                   @RequestPart MultipartFile file) {
        try {
            byte[] data = file.getBytes();
            fileStorageService.uploadChunk(uploadId, chunkIndex, data);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("uploadId", uploadId);
            result.put("chunkIndex", chunkIndex);
            result.put("received", data.length);
            return Result.success("分片接收成功", result);
        } catch (Exception e) {
            log.error("分片上传失败, uploadId: {}, chunkIndex: {}", uploadId, chunkIndex, e);
            fileRecordService.markFailed(uploadId);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                    "分片上传失败: " + e.getMessage());
        }
    }

    @OperationLog("完成上传")
    @Operation(summary = "完成分片上传，合并文件")
    @RateLimiter(limit = 10, windowSeconds = 60)
    @PostMapping("/complete")
    public Result<FileVO> complete(@RequestBody @Valid UploadCompleteDto dto) {
        try {
            CompleteUploadResult result = fileStorageService.completeUpload(dto.getUploadId());
            String fileUrl = fileStorageService.getFileUrl(result.storagePath());
            UploadedFile record = fileRecordService.markCompleted(
                    dto.getUploadId(), result.storagePath(), fileUrl, result.actualFileSize());
            return Result.success("上传完成", FileVO.from(record));
        } catch (Exception e) {
            log.error("完成上传失败, uploadId: {}", dto.getUploadId(), e);
            fileRecordService.markFailed(dto.getUploadId());
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                    "上传完成失败: " + e.getMessage());
        }
    }

    @Operation(summary = "文件列表", description = "支持按 orderNum 筛选，不传则查当前用户全部文件")
    @GetMapping("/list")
    public Result<Map<String, Object>> listFiles(@RequestParam(required = false) String orderNum,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.getUserId();
        Page<UploadedFile> filePage = (orderNum != null && !orderNum.isBlank())
                ? fileRecordService.listByOrderAndUser(orderNum, userId, page, size)
                : fileRecordService.listByUser(userId, page, size);

        List<FileVO> files = filePage.getContent().stream().map(FileVO::from).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", files);
        result.put("currentPage", filePage.getNumber());
        result.put("totalPages", filePage.getTotalPages());
        result.put("totalElements", filePage.getTotalElements());
        return Result.success(result);
    }

    @Operation(summary = "下载文件")
    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        UploadedFile record = fileRecordService.getById(id);

        // 校验所有权
        if (!record.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.FILE_ACCESS_DENIED);
        }

        InputStream ins = fileStorageService.getFileInputStream(record.getStoragePath());
        Resource resource = new InputStreamResource(ins);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        record.getMimeType() != null ? record.getMimeType() : "application/octet-stream"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + record.getOriginalName() + "\"")
                .body(resource);
    }

    @OperationLog("删除文件")
    @Operation(summary = "删除文件及记录")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @DeleteMapping("/{id}")
    public Result<Void> deleteFile(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        fileRecordService.deleteRecord(id, userId);
        return Result.success("删除成功");
    }

    @OperationLog("绑定文件到订单")
    @Operation(summary = "将文件批量绑定到订单")
    @RateLimiter(limit = 10, windowSeconds = 60)
    @PostMapping("/bind")
    public Result<Map<String, Object>> bindFiles(@RequestBody @Valid BindFileDto dto) {
        Long userId = UserContext.getUserId();
        List<UploadedFile> files = fileRecordService.bindToOrder(dto.getFileIds(), dto.getOrderNum(), userId);

        List<FileVO> fileVos = files.stream().map(FileVO::from).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", fileVos);
        result.put("orderNum", dto.getOrderNum());
        return Result.success("绑定成功", result);
    }
}
