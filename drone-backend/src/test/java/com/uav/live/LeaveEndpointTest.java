package com.uav.live;

import com.uav.server.util.JwtUtil;
import com.uav.user.mapper.UserRecordRepository;
import com.uav.user.pojo.entity.User;
import com.uav.user.pojo.entity.UserRecord;
import com.uav.user.mapper.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1B-4b 微端点测试：POST /live/leave?deviceId=（JWT）。
 * 仅结束当前登录用户在该设备的未关闭观看记录；LiveSession 与推流完全不受影响；幂等。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LeaveEndpointTest {

    @LocalServerPort
    int port;

    @Autowired
    WebApplicationContext wac;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserRecordRepository userRecordRepository;

    @Autowired
    com.uav.uav.mapper.UavRepository uavRepository;

    @Autowired
    com.uav.live.service.LiveSessionService liveSessionService;

    MockMvc mockMvc;

    private long rid;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        rid = System.nanoTime();
    }

    @Test
    @DisplayName("leave：本人观看记录 end_time 补齐，LiveSession 与推流状态不受影响")
    void leaveClosesCallerRecordOnly() throws Exception {
        User viewer = newUser(0);
        String deviceId = "leave-dev-" + rid;
        registerUav(deviceId);
        UserRecord record = openRecord(viewer.getUserName(), deviceId);

        mockMvc.perform(post("/live/leave?deviceId=" + deviceId)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                viewer.getId(), viewer.getUserName(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("观看记录已结束"));

        UserRecord after = userRecordRepository.findById(record.getId()).orElseThrow();
        assertThat(after.getEnd_time()).isNotNull();
        // LiveSession 不受影响（未开播仍为 IDLE，无任何启动痕迹）
        assertThat(liveSessionService.isRunning(deviceId)).isFalse();
    }

    @Test
    @DisplayName("leave 幂等：无开放记录返回 200")
    void leaveIsIdempotent() throws Exception {
        User viewer = newUser(0);
        String deviceId = "leave-idem-" + rid;
        registerUav(deviceId);
        UserRecord record = openRecord(viewer.getUserName(), deviceId);

        mockMvc.perform(post("/live/leave?deviceId=" + deviceId)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                viewer.getId(), viewer.getUserName(), 0)))
                .andExpect(status().isOk());
        // 第二次：已无开放记录，仍 200
        mockMvc.perform(post("/live/leave?deviceId=" + deviceId)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                viewer.getId(), viewer.getUserName(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("观看记录已结束"));

        assertThat(userRecordRepository.findById(record.getId()).orElseThrow().getEnd_time()).isNotNull();
    }

    @Test
    @DisplayName("leave 仅影响本人：其他观众的开放记录不被动")
    void leaveOnlyTouchesCallerRecord() throws Exception {
        User viewer = newUser(0);
        User otherViewer = newUser(0);
        String deviceId = "leave-other-" + rid;
        registerUav(deviceId);
        UserRecord myRecord = openRecord(viewer.getUserName(), deviceId);
        UserRecord otherRecord = openRecord(otherViewer.getUserName(), deviceId);

        mockMvc.perform(post("/live/leave?deviceId=" + deviceId)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                viewer.getId(), viewer.getUserName(), 0)))
                .andExpect(status().isOk());

        assertThat(userRecordRepository.findById(myRecord.getId()).orElseThrow().getEnd_time()).isNotNull();
        assertThat(userRecordRepository.findById(otherRecord.getId()).orElseThrow().getEnd_time()).isNull();
    }

    @Test
    @DisplayName("leave 未注册设备 → 404")
    void leaveUnknownDeviceNotFound() throws Exception {
        User viewer = newUser(0);

        mockMvc.perform(post("/live/leave?deviceId=unknown-" + rid)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                viewer.getId(), viewer.getUserName(), 0)))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    private User newUser(int role) {
        User user = new User();
        user.setUserName("leave" + rid + "-" + role + "-" + java.util.UUID.randomUUID()
                .toString().substring(0, 6));
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
    }

    private void registerUav(String deviceId) {
        com.uav.uav.pojo.entity.Uav uav = new com.uav.uav.pojo.entity.Uav();
        uav.setUavName("lv-" + deviceId);
        uav.setDjiId(deviceId);
        uav.setOnlineStatus('1');
        uav.setControllerModel("cm");
        uav.setIsAvailable('1');
        uavRepository.save(uav);
    }

    private UserRecord openRecord(String userName, String deviceId) {
        UserRecord record = new UserRecord();
        record.setUserName(userName);
        record.setDjiId(deviceId);
        record.setStart_time(LocalDateTime.now());
        return userRecordRepository.save(record);
    }
}
