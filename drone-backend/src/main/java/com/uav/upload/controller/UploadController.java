package com.uav.upload.controller;

import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.annotation.SkipJwt;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import com.uav.upload.dto.BindUploadDto;
import com.uav.upload.dto.UploadSignDto;
import com.uav.upload.entity.UploadedFile;
import com.uav.upload.service.OssPreSignService;
import com.uav.upload.service.UploadRecordService;
import com.uav.upload.vo.UploadSignVO;
import com.uav.upload.vo.UploadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Tag(name = "Upload API", description = "文件上传管理")
@RestController
@RequestMapping("/upload")
@Slf4j
public class UploadController {

    @Autowired
    private UploadRecordService uploadRecordService;

    @Autowired
    private OssPreSignService ossPreSignService;

    @OperationLog("获取OSS预签名上传URL")
    @Operation(summary = "获取上传凭证", description = "返回 OSS 预签名 PUT URL，客户端凭此直传文件到 OSS")
    @RateLimiter(limit = 20, windowSeconds = 60)
    @PostMapping("/sign")
    public Result<UploadSignVO> sign(@RequestBody @Valid UploadSignDto dto) {
        Long userId = UserContext.getUserId();
        UploadSignVO vo = ossPreSignService.generateUploadSign(
                dto.getFileName(), dto.getFileSize(), dto.getMimeType(), userId, dto.getOrderNum());
        return Result.success("预签名 URL 已生成", vo);
    }

    @SkipJwt
    @Operation(summary = "OSS 上传回调", description = "OSS 在上传完成后自动回调此接口")
    @PostMapping("/callback")
    public Result<Void> ossCallback(@RequestBody Map<String, String> body) {
        long fileId = Long.parseLong(body.get("fileId"));
        String object = body.get("object");
        long size = Long.parseLong(body.getOrDefault("size", "0"));
        ossPreSignService.handleCallback(fileId, object, size);
        return Result.success();
    }

    @OperationLog("确认上传完成")
    @Operation(summary = "确认上传完成", description = "客户端直传 OSS 完成后手动确认，回调模式可跳过此步骤")
    @RateLimiter(limit = 20, windowSeconds = 60)
    @PostMapping("/confirm/{fileId}")
    public Result<UploadVO> confirmUpload(@PathVariable Long fileId) {
        Long userId = UserContext.getUserId();
        UploadVO vo = ossPreSignService.confirmUpload(fileId, userId);
        return Result.success("上传确认完成", vo);
    }

    @Operation(summary = "文件列表", description = "支持按 orderNum 筛选，不传则查当前用户全部文件")
    @GetMapping("/list")
    public Result<Map<String, Object>> listFiles(@RequestParam(required = false) String orderNum,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.getUserId();
        Page<UploadedFile> filePage = (orderNum != null && !orderNum.isBlank())
                ? uploadRecordService.listByOrderAndUser(orderNum, userId, page, size)
                : uploadRecordService.listByUser(userId, page, size);

        List<UploadVO> files = filePage.getContent().stream().map(UploadVO::from).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", files);
        result.put("currentPage", filePage.getNumber());
        result.put("totalPages", filePage.getTotalPages());
        result.put("totalElements", filePage.getTotalElements());
        return Result.success(result);
    }

    @Operation(summary = "获取文件下载链接", description = "返回 OSS 预签名 GET URL，30 分钟有效")
    @GetMapping("/download/{id}")
    public Result<Map<String, String>> download(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        String url = ossPreSignService.generateDownloadSign(id, userId);
        return Result.success(Map.of("url", url));
    }

    @OperationLog("删除文件")
    @Operation(summary = "删除文件及记录")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @DeleteMapping("/{id}")
    public Result<Void> deleteFile(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        uploadRecordService.deleteRecord(id, userId);
        return Result.success("删除成功");
    }

    @OperationLog("绑定文件到订单")
    @Operation(summary = "将文件批量绑定到订单")
    @RateLimiter(limit = 10, windowSeconds = 60)
    @PostMapping("/bind")
    public Result<Map<String, Object>> bindFiles(@RequestBody @Valid BindUploadDto dto) {
        Long userId = UserContext.getUserId();
        List<UploadedFile> files = uploadRecordService.bindToOrder(
                dto.getFileIds(), dto.getOrderNum(), userId, UserContext.getRole());

        List<UploadVO> fileVos = files.stream().map(UploadVO::from).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", fileVos);
        result.put("orderNum", dto.getOrderNum());
        return Result.success("绑定成功", result);
    }
}
