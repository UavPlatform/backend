package com.uav.server.file.service;

import com.uav.server.file.entity.UploadedFile;
import org.springframework.data.domain.Page;

import java.util.List;

public interface FileRecordService {

    /** 创建上传初始化记录 */
    UploadedFile createInitRecord(String uploadId, String fileName, long fileSize,
                                  String mimeType, int totalChunks, Long userId);

    /** 标记上传完成 */
    UploadedFile markCompleted(String uploadId, String storagePath, String fileUrl, long actualFileSize);

    /** 标记上传失败 */
    void markFailed(String uploadId);

    /** 根据 ID 查询 */
    UploadedFile getById(Long id);

    /** 按用户分页查询 */
    Page<UploadedFile> listByUser(Long userId, int page, int size);

    /** 按订单号分页查询 */
    Page<UploadedFile> listByOrder(String orderNum, int page, int size);

    /** 按订单号+用户分页查询 */
    Page<UploadedFile> listByOrderAndUser(String orderNum, Long userId, int page, int size);

    /** 批量绑定文件到订单，返回更新后的文件列表 */
    List<UploadedFile> bindToOrder(List<Long> fileIds, String orderNum, Long userId);

    /** 删除文件记录（含校验所有权） */
    void deleteRecord(Long id, Long userId);
}
