package com.uav.dispatch;

import com.uav.dispatch.mapper.WaybillDraftRepository;
import com.uav.dispatch.pojo.dto.WaybillDraftDTO;
import com.uav.dispatch.pojo.entity.WaybillDraft;
import com.uav.dispatch.service.WaybillDraftService;
import com.uav.server.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WaybillDraftServiceTest {
    private final WaybillDraftRepository repository = mock(WaybillDraftRepository.class);
    private final WaybillDraftService service = new WaybillDraftService(repository);

    private WaybillDraftDTO input(Long revision) {
        return new WaybillDraftDTO(1, 0, Map.of("weightMin", "1."), revision);
    }

    private WaybillDraft saved() {
        WaybillDraft draft = new WaybillDraft();
        draft.setUserId(7L);
        draft.setRevision(2L);
        draft.setSchemaVersion(1);
        draft.setFieldsJson("{}");
        return draft;
    }

    @Test
    void missingDraftIsNullAndReadUsesOnlyCurrentOwner() {
        when(repository.findById(7L)).thenReturn(Optional.empty());
        assertThat(service.get(7L)).isNull();
        verify(repository).findById(7L);
    }

    @Test
    void incompleteInputsCanBeSavedWithoutCreatingAnOrder() {
        when(repository.findById(7L)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            WaybillDraft draft = call.getArgument(0);
            draft.setRevision(0L);
            return draft;
        });
        assertThat(service.save(7L, input(null)).fields()).containsEntry("weightMin", "1.");
    }

    @Test
    void staleRevisionDoesNotWrite() {
        when(repository.findById(7L)).thenReturn(Optional.of(saved()));
        assertThatThrownBy(() -> service.save(7L, input(1L)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("WAYBILL_DRAFT_CONFLICT");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDatabaseUpdateIsReportedAsConflict() {
        when(repository.findById(7L)).thenReturn(Optional.of(saved()));
        when(repository.saveAndFlush(any())).thenThrow(
                new ObjectOptimisticLockingFailureException(WaybillDraft.class, 7L));
        assertThatThrownBy(() -> service.save(7L, input(2L)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("WAYBILL_DRAFT_CONFLICT");
    }

    @Test
    void simultaneousFirstInsertIsReportedAsConflict() {
        when(repository.findById(7L)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        assertThatThrownBy(() -> service.save(7L, input(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("WAYBILL_DRAFT_CONFLICT");
    }

    @Test
    void clientCannotSavePriceStateOrLocalPhotoFields() {
        for (String key : new String[]{"totalAmount", "orderStatus", "cargo.photo.private-path"}) {
            WaybillDraftDTO request = new WaybillDraftDTO(1, 0, Map.of(key, "100"), null);
            assertThatThrownBy(() -> service.save(7L, request)).isInstanceOf(BusinessException.class);
        }
        verify(repository, never()).saveAndFlush(any());
    }
}
