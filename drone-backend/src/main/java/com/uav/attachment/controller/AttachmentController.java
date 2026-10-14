package com.uav.attachment.controller;

import com.uav.attachment.service.AttachmentService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 1B-9b 交付物附件接口（presigned URL 模式，后端不经手文件字节流）。
 * 授权：上传=任务接单飞手或任务所有者（写路径不变）；查看/下载=任务所有者、接单飞手与管理员（role=2 只读）。
 */
@Tag(name = "Task Attachment API", description = "任务交付物附件（MinIO presigned URL）")
@RestController
@RequestMapping("/tasks/{taskNum}/attachments")
public class AttachmentController {

    private final com.uav.attachment.service.AttachmentService attachmentService;

    public AttachmentController(com.uav.attachment.service.AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @OperationLog("请求交付物上传凭证")
    @Operation(summary = "获取交付物上传凭证",
            description = "任务所有者或接单飞手可调用；校验类型（图片 jpeg/png/webp ≤20MB，视频 mp4/mov ≤500MB）后"
                    + "返回 presigned PUT URL（15 分钟有效）并登记 PENDING；直传后须调用 confirm-upload",
            parameters = {
                    @Parameter(name = "taskNum", description = "任务编号", required = true),
                    @Parameter(name = "fileName", description = "原始文件名", required = true),
                    @Parameter(name = "contentType", description = "内容类型（image/jpeg 等）", required = true),
                    @Parameter(name = "sizeBytes", description = "文件大小（字节）", required = true)
            })
    @PostMapping("/upload-url")
    public Result<Map<String, Object>> uploadUrl(@PathVariable String taskNum,
                                                 @RequestParam String fileName,
                                                 @RequestParam String contentType,
                                                 @RequestParam Long sizeBytes) {
        Long callerId = com.uav.server.util.UserContext.getUserId();
        return Result.success(attachmentService.requestUploadUrl(callerId, taskNum, fileName, contentType, sizeBytes));
    }

    @Operation(summary = "确认附件上传完成", operationId = "confirmAttachmentUpload", description = "仅原上传者可确认；验证对象存在且大小一致后置为 UPLOADED，重复确认幂等")
    @PostMapping("/confirm-upload")
    public Result<Map<String, Object>> confirmUpload(@PathVariable String taskNum,
                                                    @RequestParam String objectKey) {
        return Result.success(attachmentService.confirmUpload(
                com.uav.server.util.UserContext.getUserId(), taskNum, objectKey));
    }

    @OperationLog("查看交付物列表")
    @Operation(summary = "附件列表",
            description = "任务所有者、接单飞手与管理员（role=2，监管端只读）可看；"
                    + "仅返回已确认或核验通过的历史附件（uploadStatus=UPLOADED），校验对象存在且大小一致后附下载 URL",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable String taskNum) {
        Long callerId = com.uav.server.util.UserContext.getUserId();
        return Result.success(attachmentService.listAttachments(callerId,
                com.uav.server.util.UserContext.getRole(), taskNum));
    }

    @OperationLog("请求交付物下载凭证")
    @Operation(summary = "获取交付物下载凭证",
            description = "任务所有者、接单飞手与管理员（role=2，监管端只读）可看",
            parameters = {
                    @Parameter(name = "taskNum", description = "任务编号", required = true),
                    @Parameter(name = "objectKey", description = "附件对象键", required = true)
            })
    @GetMapping("/download-url")
    public Result<Map<String, Object>> downloadUrl(@PathVariable String taskNum,
                                                   @RequestParam String objectKey) {
        Long callerId = com.uav.server.util.UserContext.getUserId();
        return Result.success(attachmentService.downloadUrl(callerId,
                com.uav.server.util.UserContext.getRole(), taskNum, objectKey));
    }
}
