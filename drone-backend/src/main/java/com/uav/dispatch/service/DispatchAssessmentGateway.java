package com.uav.dispatch.service;

import com.uav.dispatch.pojo.vo.DispatchEvaluationVO;
import java.util.Map;

/** REQ-SHIP-PUB-003 / ADR-0007: trusted server-side data source, never client risk flags. */
public interface DispatchAssessmentGateway {
    boolean available();
    DispatchEvaluationVO evaluate(Map<String, String> fields);
}
