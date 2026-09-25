package com.uav.task.mapper;

import com.uav.task.pojo.entity.TaskAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, Long> {

    List<TaskAttachment> findByTaskNumOrderByCreateTimeAsc(String taskNum);

    Optional<TaskAttachment> findByTaskNumAndObjectKey(String taskNum, String objectKey);

    boolean existsByTaskNumAndObjectKey(String taskNum, String objectKey);
}
