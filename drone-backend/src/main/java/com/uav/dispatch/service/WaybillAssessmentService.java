package com.uav.dispatch.service;

import com.alibaba.fastjson.JSON;
import com.uav.dispatch.mapper.WaybillAssessmentRepository;
import com.uav.dispatch.pojo.dto.WaybillDraftDTO;
import com.uav.dispatch.pojo.entity.WaybillAssessment;
import com.uav.dispatch.pojo.vo.WaybillAssessmentVO;
import com.uav.dispatch.pojo.vo.DispatchEvaluationVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class WaybillAssessmentService {
    private final WaybillAssessmentRepository assessments;
    private final WaybillSubmissionValidator validator;
    private final DispatchAssessmentGateway gateway;
    private final DispatchPricingService pricing;
    public WaybillAssessmentService(WaybillAssessmentRepository assessments,
            WaybillSubmissionValidator validator, DispatchAssessmentGateway gateway, DispatchPricingService pricing) {
        this.assessments = assessments;
        this.validator = validator;
        this.gateway = gateway;
        this.pricing = pricing;
    }

    @Transactional
    public WaybillAssessmentVO assess(Long owner, WaybillDraftDTO input) {
        validator.validate(input);
        if (!gateway.available()) throw unavailable();
        DispatchEvaluationVO evaluation;
        try { evaluation = gateway.evaluate(input.fields()); }
        catch (RuntimeException exception) { throw unavailable(); }
        if (evaluation == null || !evaluation.complete()) throw unavailable();
        WaybillAssessment assessment = new WaybillAssessment();
        assessment.setId(UUID.randomUUID().toString());
        assessment.setUserId(owner);
        assessment.setFingerprint(fingerprint(input.fields()));
        assessment.setFieldsJson(JSON.toJSONString(input.fields()));
        var quote = pricing.quote(input.fields());
        assessment.setQuoteJson(DispatchPricingService.encode(quote));
        assessment.setEvaluationJson(JSON.toJSONString(Map.of("airspace", evaluation.airspace(),
                "source", evaluation.source(), "distanceMeters", evaluation.distanceMeters(),
                "flightMinutes", evaluation.flightMinutes(), "altitudeDifferenceMeters", evaluation.altitudeDifferenceMeters(),
                "terrain", evaluation.terrain(), "warnings", evaluation.warnings())));
        // Technical freshness bound only; not the undecided dispatch timeout.
        assessment.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        assessments.save(assessment);
        return new WaybillAssessmentVO(assessment.getId(), assessment.getExpiresAt(),
                !evaluation.airspace().equals("PROHIBITED"), evaluation, quote);
    }

    public WaybillAssessment requireValid(Long owner, String id, WaybillDraftDTO input) {
        validator.validate(input);
        WaybillAssessment assessment = assessments.findById(id)
                .filter(a -> a.getUserId().equals(owner)).orElseThrow(this::invalid);
        if (!assessment.getExpiresAt().isAfter(LocalDateTime.now())
                || !assessment.getFingerprint().equals(fingerprint(input.fields()))) throw invalid();
        var saved = JSON.parseObject(assessment.getEvaluationJson());
        var evaluation = new DispatchEvaluationVO(saved.getString("airspace"), saved.getString("source"),
                saved.getBigDecimal("distanceMeters"), saved.getBigDecimal("flightMinutes"),
                saved.getBigDecimal("altitudeDifferenceMeters"), saved.getString("terrain"),
                saved.getJSONArray("warnings").toJavaList(String.class));
        if (!gateway.available() || evaluation == null || !evaluation.complete()) throw unavailable();
        if (evaluation.airspace().equals("PROHIBITED")) throw new BusinessException(
                HttpStatus.CONFLICT, ApiErrorCode.AIRSPACE_PROHIBITED);
        return assessment;
    }

    public static String fingerprint(Map<String, String> fields) {
        try {
            byte[] bytes = JSON.toJSONString(new TreeMap<>(fields)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private BusinessException invalid() { return new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.ASSESSMENT_INVALID); }
    private BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.DISPATCH_UNAVAILABLE,
                "空域、航程与地形评估暂不可用，请稍后重试；尚未创建订单或付款");
    }
}
