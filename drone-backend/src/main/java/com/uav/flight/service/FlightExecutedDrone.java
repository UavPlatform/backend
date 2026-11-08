package com.uav.flight.service;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.server.util.SerialNumbers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ADR-0007 #6/#9: the order records the aircraft that actually reported events, never a manual entry.
 *
 * <p>The v1 write path was the deleted connection-registration endpoint. Event v2 has no such step, so the
 * identity comes from the accepted event projection instead. The newest event by capture time wins, which makes
 * the result independent of upload order: a late backfill of an older aircraft cannot replace the current one.
 */
@Service
public class FlightExecutedDrone {
    private final JdbcTemplate jdbc;
    private final AircraftModelRepository models;

    public FlightExecutedDrone(JdbcTemplate jdbc, AircraftModelRepository models) {
        this.jdbc = jdbc; this.models = models;
    }

    public void recordNewest(DispatchOrder order, String orderId) {
        List<Identity> rows = jdbc.query("SELECT serial_number, model_code FROM flight_projection_event WHERE order_id = ? "
                        + "ORDER BY captured_at_ms DESC, event_id DESC LIMIT 1",
                (rs, n) -> new Identity(new String(rs.getBytes(1), StandardCharsets.UTF_8),
                        new String(rs.getBytes(2), StandardCharsets.UTF_8)), orderId);
        if (rows.isEmpty()) return;
        Identity identity = rows.getFirst();
        String serial = SerialNumbers.normalize(identity.serialNumber());
        if (serial == null || serial.isBlank() || serial.length() > SerialNumbers.MAX_LENGTH) return;
        // Unknown model stays unknown; never guess a model id from a partial or aliased code.
        Optional<AircraftModel> model = identity.modelCode() == null || identity.modelCode().isBlank()
                ? Optional.empty() : models.findByModelCodeIgnoreCase(identity.modelCode());
        Long modelId = model.map(AircraftModel::getId).orElse(null);
        String modelName = model.map(AircraftModel::getDisplayName).orElse(null);
        if (serial.equals(order.getExecutedSerialNumber())
                && Objects.equals(identity.modelCode(), order.getExecutedModelCode())
                && Objects.equals(modelName, order.getExecutedModelName())
                && Objects.equals(modelId, order.getExecutedAircraftModelId())) return;
        order.setExecutedSerialNumber(serial);
        order.setExecutedModelCode(identity.modelCode());
        order.setExecutedModelName(modelName);
        order.setExecutedAircraftModelId(modelId);
    }

    private record Identity(String serialNumber, String modelCode) { }
}
