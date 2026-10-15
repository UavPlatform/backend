package com.uav.attachment;

import com.uav.attachment.service.AttachmentService;
import com.uav.attachment.service.MinioStorageService;
import com.uav.task.mapper.*;
import com.uav.task.pojo.entity.*;
import com.uav.task.service.TaskReadAccess;
import com.uav.server.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttachmentVerificationTest {
    TaskRepository tasks = mock(TaskRepository.class);
    TaskAttachmentRepository attachments = mock(TaskAttachmentRepository.class);
    TaskAssignmentRepository assignments = mock(TaskAssignmentRepository.class);
    MinioStorageService storage = mock(MinioStorageService.class);
    TaskReadAccess access = mock(TaskReadAccess.class);
    AttachmentService service = new AttachmentService(tasks, attachments, assignments, storage, access);
    TaskAttachment row = new TaskAttachment();

    @BeforeEach void setup() {
        Task task = new Task(); task.setId(1L); task.setUserId(7L);
        when(tasks.findByTaskNum("T1")).thenReturn(Optional.of(task));
        when(storage.isConfigured()).thenReturn(true);
        when(access.canRead(task, 7L, 0)).thenReturn(true);
        row.setTaskNum("T1"); row.setUploaderId(7L); row.setObjectKey("key");
        row.setSizeBytes(1024L); row.setFileName("proof.jpg"); row.setContentType("image/jpeg");
        when(attachments.findByTaskNumOrderByCreateTimeAsc("T1")).thenReturn(List.of(row));
        when(attachments.findByTaskNumAndObjectKey("T1", "key")).thenReturn(Optional.of(row));
    }

    @Test void pendingCannotBeListedDownloadedOrUsedAsEvidenceEvenWhenObjectExists() {
        when(storage.isValidObject("key", 1024L)).thenReturn(true);
        assertThat(service.listAttachments(7L, 0, "T1")).isEmpty();
        assertThat(service.hasVerifiedEvidence("T1")).isFalse();
        assertThatThrownBy(() -> service.downloadUrl(7L, 0, "T1", "key")).isInstanceOf(BusinessException.class);
        verify(storage, never()).presignedGet(anyString(), anyInt());
    }

    @Test void missingOrWrongSizedObjectCannotBeConfirmed() {
        when(storage.isValidObject("key", 1024L)).thenReturn(false);
        assertThatThrownBy(() -> service.confirmUpload(7L, "T1", "key")).isInstanceOf(BusinessException.class);
        assertThat(row.getUploadStatus()).isEqualTo("PENDING");
        verify(attachments, never()).save(any());
    }

    @Test void confirmationIsIdempotentAndEnablesEvidence() {
        when(storage.isValidObject("key", 1024L)).thenReturn(true);
        service.confirmUpload(7L, "T1", "key");
        assertThat(service.confirmUpload(7L, "T1", "key")).containsEntry("uploadStatus", "UPLOADED");
        assertThat(service.listAttachments(7L, 0, "T1")).hasSize(1);
        assertThat(service.hasVerifiedEvidence("T1")).isTrue();
    }

    @Test void deletedUploadedObjectIsHiddenAndCannotBeUsed() {
        row.setUploadStatus("UPLOADED");
        when(storage.isValidObject("key", 1024L)).thenReturn(false);
        assertThat(service.listAttachments(7L, 0, "T1")).isEmpty();
        assertThat(service.hasVerifiedEvidence("T1")).isFalse();
        assertThatThrownBy(() -> service.downloadUrl(7L, 0, "T1", "key")).isInstanceOf(BusinessException.class);
    }

    @Test void legacyRowsMustPassStorageValidation() {
        row.setUploadStatus("LEGACY");
        when(storage.isValidObject("key", 1024L)).thenReturn(false, true);
        assertThat(service.listAttachments(7L, 0, "T1")).isEmpty();
        assertThat(service.listAttachments(7L, 0, "T1")).hasSize(1);
    }

    @Test void emptyEvidenceDoesNotRequireStorageConfiguration() {
        when(attachments.findByTaskNumOrderByCreateTimeAsc("T1")).thenReturn(List.of());
        clearInvocations(storage);
        assertThat(service.hasVerifiedEvidence("T1")).isFalse();
        verifyNoInteractions(storage);
    }

    @Test void storageOutageFailsClosed() {
        row.setUploadStatus("UPLOADED");
        when(storage.isValidObject("key", 1024L)).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> service.hasVerifiedEvidence("T1")).isInstanceOf(IllegalStateException.class);
        verify(storage, never()).presignedGet(anyString(), anyInt());
    }

    @Test void taskOwnerCannotConfirmAnotherUploadersTicket() {
        row.setUploaderId(9L);
        assertThatThrownBy(() -> service.confirmUpload(7L, "T1", "key")).isInstanceOf(BusinessException.class);
        verify(storage, never()).isValidObject(anyString(), anyLong());
    }
}
