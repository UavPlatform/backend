package com.uav.dispatch;

import com.uav.dispatch.pojo.dto.WaybillDraftDTO;
import com.uav.dispatch.service.WaybillSubmissionValidator;
import com.uav.server.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class WaybillSubmissionValidatorTest {
    private final WaybillSubmissionValidator validator = new WaybillSubmissionValidator();
    @Test void completeOrdinaryCargoAndScheduledLocalOrUtcTimeAreAccepted() {
        var f = DispatchTestData.fields();
        validator.validate(new WaybillDraftDTO(1, 4, f, null));
        f.put("timeMode", "scheduled");
        for (String timestamp : new String[]{"2099-01-01T12:00:00", "2099-01-01T12:00:00Z", "2099-01-01T12:00:00+08:00"}) {
            f.put("scheduledAt", timestamp);
            validator.validate(new WaybillDraftDTO(1, 4, f, null));
        }
    }
    @ParameterizedTest
    @CsvSource({"category,危险品", "weightMin,-1", "weightMin,NaN", "weightMin,Infinity", "weightMax,1", "length,0",
            "count,1.5", "count,0", "start.lat,91", "end.lng,181", "start.phone,123", "start.landing,maybe",
            "timeMode,unknown", "urgent,unknown"})
    void malformedFullSubmissionsAreRejected(String key, String value) {
        var f = DispatchTestData.fields(); f.put(key, value);
        assertThatThrownBy(() -> validator.validate(new WaybillDraftDTO(1, 4, f, null))).isInstanceOf(BusinessException.class);
    }
    @Test void winchRequiresBothBoundsAndPastAppointmentsAreRejected() {
        var f = DispatchTestData.fields(); f.put("start.landing", "no"); f.put("start.winchMin", "5");
        assertThatThrownBy(() -> validator.validate(new WaybillDraftDTO(1, 4, f, null))).isInstanceOf(BusinessException.class);
        f.put("start.winchMax", "10"); validator.validate(new WaybillDraftDTO(1, 4, f, null));
        f.put("timeMode", "scheduled"); f.put("scheduledAt", "2020-01-01T12:00:00");
        assertThatThrownBy(() -> validator.validate(new WaybillDraftDTO(1, 4, f, null))).isInstanceOf(BusinessException.class);
    }
}
