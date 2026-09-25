package com.uav.live;

import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.uav.pojo.entity.Uav;
import com.uav.user.mapper.UserRecordRepository;
import com.uav.user.pojo.entity.UserRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 微端点测试（1B-4b）：{@code POST /live/leave?deviceId=}（JWT）。
 * 仅结束当前登录用户在该设备的未关闭观看记录；LiveSession 与推流完全不受影响；幂等。
 * MockMvc 集成测试（R9/O4）。
 *
 * <p>R2/R4：继承 {@link IntegrationTestBase}（MOCK + {@code @AutoConfigureMockMvc} + {@code @Transactional}），
 * 删除仅声明未使用的 {@code RANDOM_PORT} 与 {@code @LocalServerPort}（避免 §9「环境声明诚实」误判），
 * 不再手工 {@code MockMvcBuilders.webAppContextSetup}；R3：token 由 {@link TestAccounts} 真实登录取得；
 * R5：唯一 deviceId 由 {@code UniqueNames} 生成（替代 {@code System.nanoTime()}），隔离靠事务回滚。
 */
class LeaveEndpointIT extends IntegrationTestBase {

    @Autowired
    private UserRecordRepository userRecordRepository;

    @Autowired
    private com.uav.uav.mapper.UavRepository uavRepository;

    @Autowired
    private com.uav.live.service.LiveSessionService liveSessionService;

    @Test
    @DisplayName("leave：本人观看记录 end_time 补齐，LiveSession 与推流状态不受影响")
    void leaveClosesCallerRecordOnly() throws Exception {
        TestAccounts.Account viewer = accounts().registerUser();
        String deviceId = UniqueNames.unique("leave-dev");
        registerUav(deviceId);
        UserRecord record = openRecord(viewer.userName(), deviceId);

        mockMvc.perform(post("/live/leave?deviceId=" + deviceId)
                        .header("Authorization", viewer.authorization()))
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
        TestAccounts.Account viewer = accounts().registerUser();
        String deviceId = UniqueNames.unique("leave-idem");
        registerUav(deviceId);
        UserRecord record = openRecord(viewer.userName(), deviceId);

        mockMvc.perform(post("/live/leave?deviceId=" + deviceId)
                        .header("Authorization", viewer.authorization()))
                .andExpect(status().isOk());
        // 第二次：已无开放记录，仍 200
        mockMvc.perform(post("/live/leave?deviceId=" + deviceId)
                        .header("Authorization", viewer.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("观看记录已结束"));

        assertThat(userRecordRepository.findById(record.getId()).orElseThrow().getEnd_time()).isNotNull();
    }

    @Test
    @DisplayName("leave 仅影响本人：其他观众的开放记录不被动")
    void leaveOnlyTouchesCallerRecord() throws Exception {
        TestAccounts.Account viewer = accounts().registerUser();
        TestAccounts.Account otherViewer = accounts().registerUser();
        String deviceId = UniqueNames.unique("leave-other");
        registerUav(deviceId);
        UserRecord myRecord = openRecord(viewer.userName(), deviceId);
        UserRecord otherRecord = openRecord(otherViewer.userName(), deviceId);

        mockMvc.perform(post("/live/leave?deviceId=" + deviceId)
                        .header("Authorization", viewer.authorization()))
                .andExpect(status().isOk());

        assertThat(userRecordRepository.findById(myRecord.getId()).orElseThrow().getEnd_time()).isNotNull();
        assertThat(userRecordRepository.findById(otherRecord.getId()).orElseThrow().getEnd_time()).isNull();
    }

    @Test
    @DisplayName("leave 未注册设备 → 404")
    void leaveUnknownDeviceNotFound() throws Exception {
        TestAccounts.Account viewer = accounts().registerUser();

        mockMvc.perform(post("/live/leave?deviceId=" + UniqueNames.unique("unknown"))
                        .header("Authorization", viewer.authorization()))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    private void registerUav(String deviceId) {
        Uav uav = new Uav();
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
