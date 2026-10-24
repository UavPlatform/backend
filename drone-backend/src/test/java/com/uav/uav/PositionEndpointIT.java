package com.uav.uav;

import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.uav.pojo.dto.UavStatusDto;
import com.uav.uav.service.UavStatusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /webUav/position}（frontend#12）：无位置数据时 data 为 null；
 * 有实时数据时字段形状与契约 GpsPointVO 一致。MockMvc 集成测试。
 */
class PositionEndpointIT extends IntegrationTestBase {

    private static final long SAMPLE_TIMESTAMP = 1_700_000_000_000L;

    @Autowired
    private UavStatusService uavStatusService;

    @Test
    @DisplayName("position：设备无任何位置数据 → 200，data 为 null")
    void noDataReturnsNull() throws Exception {
        TestAccounts.Account user = accounts().registerUser();
        mockMvc.perform(get("/webUav/position").param("deviceId", UniqueNames.djiId())
                        .header("Authorization", user.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("position：实时数据字段与 GpsPointVO 契约一致（timestamp 毫秒）")
    void fieldShapeMatchesContract() throws Exception {
        TestAccounts.Account user = accounts().registerUser();
        String deviceId = UniqueNames.djiId();
        UavStatusDto dto = new UavStatusDto();
        dto.setUavId(9_100_000_000L + System.nanoTime() % 1_000_000);
        dto.setDeviceId(deviceId);
        dto.setUavName("pos");
        dto.setLongitude(121.4737);
        dto.setLatitude(31.2304);
        dto.setAltitude(88.0);
        dto.setSpeed(7.5);
        dto.setBattery(76);
        dto.setFlightStatus(2);
        dto.setOperation("巡检");
        dto.setTimestamp(SAMPLE_TIMESTAMP);
        dto.setReceivedAt(SAMPLE_TIMESTAMP);
        uavStatusService.updateUavStatus(deviceId, dto);

        mockMvc.perform(get("/webUav/position").param("deviceId", deviceId)
                        .header("Authorization", user.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.longitude").value(121.4737))
                .andExpect(jsonPath("$.data.latitude").value(31.2304))
                .andExpect(jsonPath("$.data.altitude").value(88.0))
                .andExpect(jsonPath("$.data.speed").value(7.5))
                .andExpect(jsonPath("$.data.battery").value(76))
                .andExpect(jsonPath("$.data.flightStatus").value(2))
                .andExpect(jsonPath("$.data.operation").value("巡检"))
                .andExpect(jsonPath("$.data.timestamp").value(SAMPLE_TIMESTAMP));
    }
}
