package com.uav.dispatch.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.uav.dispatch.mapper.WaybillDraftRepository;
import com.uav.dispatch.pojo.dto.WaybillDraftDTO;
import com.uav.dispatch.pojo.entity.WaybillDraft;
import com.uav.dispatch.pojo.vo.WaybillDraftVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** REQ-SHIP-PUB-002: owner-scoped, partial draft storage with optimistic concurrency. */
@Service
public class WaybillDraftService {
    private static final Set<String> ALLOWED_FIELDS = allowedFields();
    private final WaybillDraftRepository repository;

    public WaybillDraftService(WaybillDraftRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public WaybillDraftVO get(Long userId) {
        return repository.findById(userId).map(this::view).orElse(null);
    }

    @Transactional
    public WaybillDraftVO save(Long userId, WaybillDraftDTO input) {
        if (!ALLOWED_FIELDS.containsAll(input.fields().keySet()) ||
                input.fields().values().stream().mapToInt(String::length).sum() > 16000) {
            throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        }
        WaybillDraft draft = repository.findById(userId).orElse(null);
        if (draft == null) {
            if (input.expectedRevision() != null) throw conflict();
            draft = new WaybillDraft();
            draft.setUserId(userId);
        } else if (!Objects.equals(draft.getRevision(), input.expectedRevision())) {
            throw conflict();
        }
        draft.setSchemaVersion(input.schemaVersion());
        draft.setStep(input.step());
        draft.setFieldsJson(JSON.toJSONString(input.fields()));
        draft.setUpdatedAt(LocalDateTime.now());
        try {
            return view(repository.saveAndFlush(draft));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
            // Handles simultaneous updates as well as two initial inserts for the same owner.
            throw conflict();
        }
    }

    private WaybillDraftVO view(WaybillDraft draft) {
        Map<String, String> fields = JSON.parseObject(draft.getFieldsJson(),
                new TypeReference<Map<String, String>>() { });
        return new WaybillDraftVO(draft.getSchemaVersion(), draft.getStep(), fields,
                draft.getRevision(), draft.getUpdatedAt());
    }

    private static BusinessException conflict() {
        return new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.WAYBILL_DRAFT_CONFLICT);
    }

    static Set<String> allowedFields() {
        Set<String> fields = new HashSet<>(Set.of("category", "weightMin", "weightMax", "length",
                "width", "height", "count", "packaging", "lifting", "declaredValue",
                "timeMode", "scheduledAt", "urgent"));
        for (String prefix : Set.of("start", "end")) {
            for (String field : Set.of("lat", "lng", "site", "landing", "winchMin", "winchMax",
                    "contact", "phone", "notes")) {
                fields.add(prefix + "." + field);
            }
        }
        return Set.copyOf(fields);
    }
}
