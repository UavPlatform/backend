package com.uav.attachment;

import com.uav.attachment.service.AttachmentService;
import com.uav.attachment.service.MinioStorageService;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.service.OrderService;
import com.uav.server.enums.MatchStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.exception.BusinessException;
import com.uav.task.mapper.*;
import com.uav.task.pojo.entity.*;
import com.uav.task.service.TaskReadAccess;
import com.uav.task.service.impl.TaskLifecycleService;
import com.uav.task.service.impl.TaskQueryService;
import com.uav.task.service.impl.TaskServiceImpl;
import com.uav.user.mapper.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskEvidenceGateTest {
    TaskRepository tasks = mock(TaskRepository.class);
    TaskAssignmentRepository assignments = mock(TaskAssignmentRepository.class);
    TaskAttachmentRepository attachments = mock(TaskAttachmentRepository.class);
    TaskApplicationRepository applications = mock(TaskApplicationRepository.class);
    OrderRepository orders = mock(OrderRepository.class);
    MinioStorageService storage = mock(MinioStorageService.class);
    Task task = new Task(); TaskAttachment evidence = new TaskAttachment();
    TaskServiceImpl service;

    @BeforeEach void setup() {
        task.setId(1L); task.setUserId(7L); task.setTaskStatus(TaskStatus.IN_PROGRESS);
        task.setMatchStatus(MatchStatus.CONFIRMED);
        TaskAssignment assignment = new TaskAssignment(); assignment.setRiderId(9L);
        when(tasks.findByTaskNumForUpdate("T1")).thenReturn(Optional.of(task));
        when(tasks.findByTaskNum("T1")).thenReturn(Optional.of(task));
        when(assignments.findByTaskId(1L)).thenReturn(Optional.of(assignment));
        when(orders.findByTaskId(1L)).thenReturn(Optional.empty());
        when(storage.isConfigured()).thenReturn(true);
        evidence.setObjectKey("key"); evidence.setSizeBytes(3L);
        when(attachments.findByTaskNumOrderByCreateTimeAsc("T1")).thenReturn(List.of(evidence));
        AttachmentService attachmentService = new AttachmentService(
                tasks, attachments, assignments, storage, mock(TaskReadAccess.class));
        TaskLifecycleService lifecycle = new TaskLifecycleService(
                tasks, assignments, applications, attachmentService, orders, mock(OrderService.class));
        TaskQueryService query = new TaskQueryService(
                tasks, applications, assignments, mock(UserRepository.class));
        service = new TaskServiceImpl(lifecycle, query);
    }

    @Test void pendingEvidenceBlocksDeliveryAndAcceptanceEvenIfObjectExists() {
        when(storage.isValidObject("key", 3L)).thenReturn(true);
        assertBlockedAtBothSteps();
    }

    @Test void missingUploadedObjectBlocksDeliveryAndAcceptance() {
        evidence.setUploadStatus("UPLOADED");
        when(storage.isValidObject("key", 3L)).thenReturn(false);
        assertBlockedAtBothSteps();
    }

    @Test void verifiedObjectAllowsDelivery() {
        evidence.setUploadStatus("UPLOADED");
        when(storage.isValidObject("key", 3L)).thenReturn(true);
        service.riderCompleteTask("T1", 9L, null);
        assertThat(task.getMatchStatus()).isEqualTo(MatchStatus.PENDING_ACCEPTANCE);
        verify(tasks).save(task);
    }

    private void assertBlockedAtBothSteps() {
        assertThatThrownBy(() -> service.riderCompleteTask("T1", 9L, null)).isInstanceOf(BusinessException.class);
        assertThat(task.getMatchStatus()).isEqualTo(MatchStatus.CONFIRMED);
        task.setTaskStatus(TaskStatus.COMPLETED); task.setMatchStatus(MatchStatus.PENDING_ACCEPTANCE);
        assertThatThrownBy(() -> service.userConfirmTask("T1", 7L)).isInstanceOf(BusinessException.class);
        assertThat(task.getMatchStatus()).isEqualTo(MatchStatus.PENDING_ACCEPTANCE);
        verify(tasks, never()).save(any());
    }
}
