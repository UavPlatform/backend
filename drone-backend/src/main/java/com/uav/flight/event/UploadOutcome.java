package com.uav.flight.event;

import com.uav.flight.pojo.vo.EventPacketAckVO;
import com.uav.server.enums.ApiErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Conflicts return instead of throwing, so the transactional method can commit the conflict evidence
 * before the controller maps the outcome to HTTP 409.
 */
public sealed interface UploadOutcome {
    record Stored(EventPacketAckVO ack) implements UploadOutcome { }
    record Rejected(HttpStatus status, ApiErrorCode code, String message) implements UploadOutcome { }
}
