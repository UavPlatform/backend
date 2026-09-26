package com.uav.live;

import com.uav.live.service.AppWebSocketService;
import com.uav.live.service.LiveSessionService;
import com.uav.live.service.impl.LiveDeviceResolver;
import com.uav.live.service.impl.LiveDeviceResolver.TaskLiveDevice;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 微任务端到端（1B-4b）：任务→设备映射（LiveDeviceResolver + TaskVo 接线）。
 * 解析链 taskId→task_assignment.rider_id→rider_uav 绑定设备→在线一台；
 * 无接单/无绑定/离线 → deviceId=null 且 liveState=IDLE。MockMvc 集成测试（R9/O4）。
 *
 * <p>R2/R4：继承 {@link IntegrationTestBase}（MOCK + {@code @AutoConfigureMockMvc} + {@code @Transactional}）。
 * 本类原先声明 {@code RANDOM_PORT} 与 {@code @LocalServerPort}，但 {@code port} 从未被使用：
 * 全部驱动为 MockMvc + Service 直调，因此改 MOCK 并删除该字段（避免 §9「环境声明诚实」误判）。
 *
 * <p>R3：owner 与飞手身份由 {@link TestAccounts} 真实注册取得，飞手注册即真实绑定唯一设备
 * （解析链因此能查到 rider_uav 绑定）；R5：唯一 deviceId / 任务名由 {@code UniqueNames}
 * 与共享工厂生成（替代 {@code System.nanoTime()}），隔离靠事务回滚。
 */
class LiveDeviceResolverIT extends IntegrationTestBase {

    @Autowired
    private AppWebSocketService appWebSocketService;

    @Autowired
    private LiveSessionService liveSessionService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private LiveDeviceResolver liveDeviceResolver;

    @Test
    @DisplayName("解析链：已接单+绑定设备在线 → deviceId+liveState（IDLE）")
    void resolvesConnectedBoundDevice() {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        var task = taskService.createTask(fixtures.twoWaypointTask());
        String deviceId = rider.djiId();
        UserContext.setUser(rider.id(), rider.userName(), rider.role());
        fixtures.awaitingRiderConfirm(task, rider.id());
        taskService.riderConfirmOrder(task.getTaskNum(), rider.id());
        appWebSocketService.requestConnection(deviceId);
        appWebSocketService.markAsConnected(deviceId);

        TaskLiveDevice resolved = liveDeviceResolver.resolveForTask(task.getId());

        assertThat(resolved.deviceId()).isEqualTo(deviceId);
        assertThat(resolved.liveState()).isEqualTo("IDLE"); // 未开播
    }

    @Test
    @DisplayName("解析链：开播后 liveState=RUNNING")
    void resolvesRunningState() {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        var task = taskService.createTask(fixtures.twoWaypointTask());
        String deviceId = rider.djiId();
        UserContext.setUser(rider.id(), rider.userName(), rider.role());
        fixtures.awaitingRiderConfirm(task, rider.id());
        taskService.riderConfirmOrder(task.getTaskNum(), rider.id());
        appWebSocketService.requestConnection(deviceId);
        appWebSocketService.markAsConnected(deviceId);
        liveSessionService.markRunning(deviceId, "drone_" + deviceId, UniqueNames.unique("req"));

        TaskLiveDevice resolved = liveDeviceResolver.resolveForTask(task.getId());

        assertThat(resolved.deviceId()).isEqualTo(deviceId);
        assertThat(resolved.liveState()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("解析链：绑定设备均离线 → deviceId=null、liveState=IDLE")
    void offlineBoundDeviceYieldsIdleNull() {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        var task = taskService.createTask(fixtures.twoWaypointTask());
        UserContext.setUser(rider.id(), rider.userName(), rider.role());
        fixtures.awaitingRiderConfirm(task, rider.id());
        taskService.riderConfirmOrder(task.getTaskNum(), rider.id());
        // 设备不连接（不 requestConnection/markAsConnected）

        TaskLiveDevice resolved = liveDeviceResolver.resolveForTask(task.getId());

        assertThat(resolved.deviceId()).isNull();
        assertThat(resolved.liveState()).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("解析链：任务未被接单 → deviceId=null、liveState=IDLE")
    void unassignedTaskYieldsIdleNull() {
        TestAccounts.Account owner = accounts().registerUser();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        var task = taskService.createTask(fixtures.twoWaypointTask());

        TaskLiveDevice resolved = liveDeviceResolver.resolveForTask(task.getId());

        assertThat(resolved.deviceId()).isNull();
        assertThat(resolved.liveState()).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("TaskVo 接线：/task/detail 返回 deviceId 与 liveState")
    void taskDetailCarriesDeviceMapping() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        var task = taskService.createTask(fixtures.twoWaypointTask());
        String deviceId = rider.djiId();
        UserContext.setUser(rider.id(), rider.userName(), rider.role());
        fixtures.awaitingRiderConfirm(task, rider.id());
        taskService.riderConfirmOrder(task.getTaskNum(), rider.id());
        appWebSocketService.requestConnection(deviceId);
        appWebSocketService.markAsConnected(deviceId);

        mockMvc.perform(get("/task/detail")
                        .param("taskNum", task.getTaskNum())
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value(deviceId))
                .andExpect(jsonPath("$.data.liveState").value("IDLE"));
    }
}
