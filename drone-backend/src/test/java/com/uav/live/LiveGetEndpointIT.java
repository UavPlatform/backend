package com.uav.live;

import com.uav.live.service.AppWebSocketService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.uav.mapper.UavRepository;
import com.uav.uav.pojo.entity.Uav;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /live/get} 状态分支（frontend#12）：设备未连接 → 409 UAV_NOT_CONNECTED；
 * 已连接但未开播 → 200 + liveState=IDLE、ackConfirmed=false。MockMvc 集成测试。
 */
class LiveGetEndpointIT extends IntegrationTestBase {

    @Autowired
    private UavRepository uavRepository;

    @Autowired
    private AppWebSocketService appWebSocketService;

    @Test
    @DisplayName("/live/get：设备未建立 WS 连接 → 409 UAV_NOT_CONNECTED")
    void notConnectedReturns409() throws Exception {
        TestAccounts.Account user = accounts().registerUser();
        String deviceId = UniqueNames.unique("lg-off");
        registerUav(deviceId);

        mockMvc.perform(post("/live/get").param("deviceId", deviceId)
                        .header("Authorization", user.authorization()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.UAV_NOT_CONNECTED.getCode()));
    }

    @Test
    @DisplayName("/live/get：设备在线但未开播 → 200，liveState=IDLE，ackConfirmed=false")
    void connectedButIdleReturnsIdle() throws Exception {
        TestAccounts.Account user = accounts().registerUser();
        String deviceId = UniqueNames.unique("lg-idle");
        registerUav(deviceId);
        appWebSocketService.requestConnection(deviceId);
        appWebSocketService.markAsConnected(deviceId);
        try {
            mockMvc.perform(post("/live/get").param("deviceId", deviceId)
                            .header("Authorization", user.authorization()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.liveState").value("IDLE"))
                    .andExpect(jsonPath("$.data.ackConfirmed").value(false));
        } finally {
            appWebSocketService.markAsDisconnected(deviceId);
        }
    }

    private void registerUav(String deviceId) {
        Uav uav = new Uav();
        uav.setUavName("lg-" + deviceId);
        uav.setDjiId(deviceId);
        uav.setOnlineStatus('1');
        uav.setControllerModel("cm");
        uav.setIsAvailable('1');
        uavRepository.save(uav);
    }
}
