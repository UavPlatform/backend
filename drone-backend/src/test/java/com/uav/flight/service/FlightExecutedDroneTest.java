package com.uav.flight.service;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** ADR-0007 #6/#9: the executed aircraft comes from accepted events, never from a guessed or manual value. */
class FlightExecutedDroneTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AircraftModelRepository models = mock(AircraftModelRepository.class);
    private final FlightExecutedDrone executed = new FlightExecutedDrone(jdbc, models);

    /** Runs the production row mapper against a stub ResultSet so the test covers the real column reads. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void projection(String serial, String modelCode) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getBytes(1)).thenReturn(serial == null ? null : serial.getBytes(StandardCharsets.UTF_8));
            when(rs.getBytes(2)).thenReturn(modelCode == null ? null : modelCode.getBytes(StandardCharsets.UTF_8));
            return List.of(mapper.mapRow(rs, 0));
        });
    }

    private static AircraftModel model(String code, String name) {
        var m = new AircraftModel();
        m.setId(9L); m.setModelCode(code); m.setDisplayName(name); m.setMaxPayloadKg(new java.math.BigDecimal("30"));
        return m;
    }

    @Test void recordsTheReportedIdentityAndMatchesTheModelCaseInsensitively() {
        projection(" 1581f5bkd225400p0001 ", "FC30");
        when(models.findByModelCodeIgnoreCase("FC30")).thenReturn(Optional.of(model("FC30", "DJI FlyCart 30")));
        var order = new DispatchOrder();

        executed.recordNewest(order, "o1");

        assertThat(order.getExecutedSerialNumber()).isEqualTo("1581F5BKD225400P0001");
        assertThat(order.getExecutedModelCode()).isEqualTo("FC30");
        assertThat(order.getExecutedModelName()).isEqualTo("DJI FlyCart 30");
        assertThat(order.getExecutedAircraftModelId()).isEqualTo(9L);
    }

    @Test void keepsAnUnknownModelUnknownInsteadOfGuessingOne() {
        projection("SN-UNKNOWN", "NOT-IN-CATALOG");
        when(models.findByModelCodeIgnoreCase("NOT-IN-CATALOG")).thenReturn(Optional.empty());
        var order = new DispatchOrder();

        executed.recordNewest(order, "o1");

        assertThat(order.getExecutedSerialNumber()).isEqualTo("SN-UNKNOWN");
        assertThat(order.getExecutedModelCode()).isEqualTo("NOT-IN-CATALOG");
        assertThat(order.getExecutedAircraftModelId()).isNull();
        assertThat(order.getExecutedModelName()).isNull();
    }

    @Test void leavesTheOrderUntouchedWhenNothingWasProjected() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var order = new DispatchOrder();

        executed.recordNewest(order, "o1");

        assertThat(order.getExecutedSerialNumber()).isNull();
        verifyNoInteractions(models);
    }

    @Test void keepsAnAlreadyRecordedIdentityAsIs() {
        projection("SN1", "FC30");
        when(models.findByModelCodeIgnoreCase("FC30")).thenReturn(Optional.of(model("FC30", "DJI FlyCart 30")));
        var order = new DispatchOrder();
        order.setExecutedSerialNumber("SN1"); order.setExecutedModelCode("FC30");
        order.setExecutedModelName("DJI FlyCart 30"); order.setExecutedAircraftModelId(9L);

        executed.recordNewest(order, "o1");

        assertThat(order.getExecutedSerialNumber()).isEqualTo("SN1");
        assertThat(order.getExecutedAircraftModelId()).isEqualTo(9L);
    }

    @Test void ignoresABlankProjectedSerial() {
        projection("   ", "FC30");
        var order = new DispatchOrder();

        executed.recordNewest(order, "o1");

        assertThat(order.getExecutedSerialNumber()).isNull();
        verifyNoInteractions(models);
    }

    @Test void readsTheNewestProjectionRowSoALateBackfillCannotRegressTheAircraft() {
        projection("SN-NEW", "FC30");
        when(models.findByModelCodeIgnoreCase("FC30")).thenReturn(Optional.of(model("FC30", "DJI FlyCart 30")));
        var order = new DispatchOrder();

        executed.recordNewest(order, "o1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("FROM flight_projection_event")
                .contains("ORDER BY captured_at_ms DESC").contains("LIMIT 1");
        assertThat(order.getExecutedSerialNumber()).isEqualTo("SN-NEW");
    }
}
