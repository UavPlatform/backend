package com.uav.upload.service;

import com.uav.upload.entity.UploadedFile;
import org.springframework.data.domain.Page;

import java.util.List;

public interface UploadRecordService {

    UploadedFile getById(Long id);

    Page<UploadedFile> listByUser(Long userId, int page, int size);

    Page<UploadedFile> listByOrder(String orderNum, int page, int size);

    Page<UploadedFile> listByOrderAndUser(String orderNum, Long userId, int page, int size);

    /** 批量绑定文件到订单，返回更新后的文件列表 */
    List<UploadedFile> bindToOrder(List<Long> fileIds, String orderNum, Long userId, Integer role);

    /** 删除文件记录（含校验所有权） */
    void deleteRecord(Long id, Long userId);
}
