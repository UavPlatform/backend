package com.uav.dispatch.pojo.vo;

import java.time.LocalDateTime;

public record WaybillAssessmentVO(String assessmentId, LocalDateTime expiresAt,
                                  boolean orderAllowed, DispatchEvaluationVO evaluation, DispatchQuoteVO quote) { }
