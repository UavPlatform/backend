package com.uav.server.file.repository;

import com.uav.server.file.entity.UploadedFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FileRepository extends JpaRepository<UploadedFile, Long> {

    Optional<UploadedFile> findByUploadId(String uploadId);

    Page<UploadedFile> findByUserIdOrderByCreateTimeDesc(Long userId, Pageable pageable);

    Page<UploadedFile> findByOrderNumOrderByCreateTimeDesc(String orderNum, Pageable pageable);

    Page<UploadedFile> findByOrderNumAndUserIdOrderByCreateTimeDesc(String orderNum, Long userId, Pageable pageable);

    List<UploadedFile> findByUserIdAndIdIn(Long userId, List<Long> ids);

    /** 查找过期未完成的上传会话 */
    List<UploadedFile> findByUploadStatusAndCreateTimeBefore(String uploadStatus, LocalDateTime deadline);
}
