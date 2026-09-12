package com.uav.live;

import com.uav.live.service.impl.LiveDeviceResolver;
import com.uav.live.service.impl.LiveDeviceResolver.TaskLiveDevice;
import com.uav.live.service.AppWebSocketService;
import com.uav.live.service.LiveSessionService;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskType;
import com.uav.server.util.JwtUtil;
import com.uav.server.util.UserContext;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.dto.WaypointDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.service.TaskService;
import com.uav.user.mapper.RiderUavRepository;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.RiderUav;
import com.uav.user.pojo.entity.User;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1B-4b 微任务端到端：任务→设备映射（LiveDeviceResolver + TaskVo 接线）。
 * 解析链 taskId→task_assignment.rider_id→rider_uav 绑定设备→在线一台；
 * 无接单/无绑定/离线 → deviceId=null 且 liveState=IDLE。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveDeviceResolverTest {

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RiderUavRepository riderUavRepository;

    @Autowired
    AppWebSocketService appWebSocketService;

    @Autowired
    LiveSessionService liveSessionService;

    @Autowired
    TaskService taskService;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    LiveDeviceResolver liveDeviceResolver;

    @Autowired
    WebApplicationContext wac;

    private long rid;

    @BeforeEach
    void setUp() {
        rid = System.nanoTime();
    }

    @AfterEach
    void restoreContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("解析链：已接单+绑定设备在线 → deviceId+liveState（IDLE）")
    void resolvesConnectedBoundDevice() {
        User owner = newUser(0);
        User rider = newUser(1);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        var task = taskService.createTask(twoWaypointTask());
        String deviceId = "ldv-online-" + rid;
        bindRider(rider.getId(), deviceId);
        UserContext.setUser(rider.getId(), rider.getUserName(), 1);
        markPaid(task);
        taskService.acceptTask(task.getTaskNum(), rider.getId());
        appWebSocketService.requestConnection(deviceId);
        appWebSocketService.markAsConnected(deviceId);

        TaskLiveDevice resolved = liveDeviceResolver.resolveForTask(task.getId());

        assertThat(resolved.deviceId()).isEqualTo(deviceId);
        assertThat(resolved.liveState()).isEqualTo("IDLE"); // 未开播
    }

    @Test
    @DisplayName("解析链：开播后 liveState=RUNNING")
    void resolvesRunningState() {
        User owner = newUser(0);
        User rider = newUser(1);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        var task = taskService.createTask(twoWaypointTask());
        String deviceId = "ldv-running-" + rid;
        bindRider(rider.getId(), deviceId);
        UserContext.setUser(rider.getId(), rider.getUserName(), 1);
        markPaid(task);
        taskService.acceptTask(task.getTaskNum(), rider.getId());
        appWebSocketService.requestConnection(deviceId);
        appWebSocketService.markAsConnected(deviceId);
        liveSessionService.markRunning(deviceId, "drone_" + deviceId, "req-" + rid);

        TaskLiveDevice resolved = liveDeviceResolver.resolveForTask(task.getId());

        assertThat(resolved.deviceId()).isEqualTo(deviceId);
        assertThat(resolved.liveState()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("解析链：绑定设备均离线 → deviceId=null、liveState=IDLE")
    void offlineBoundDeviceYieldsIdleNull() {
        User owner = newUser(0);
        User rider = newUser(1);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        var task = taskService.createTask(twoWaypointTask());
        String deviceId = "ldv-offline-" + rid;
        bindRider(rider.getId(), deviceId);
        UserContext.setUser(rider.getId(), rider.getUserName(), 1);
        markPaid(task);
        taskService.acceptTask(task.getTaskNum(), rider.getId());
        // 设备不连接（不 requestConnection/markAsConnected）

        TaskLiveDevice resolved = liveDeviceResolver.resolveForTask(task.getId());

        assertThat(resolved.deviceId()).isNull();
        assertThat(resolved.liveState()).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("解析链：任务未被接单 → deviceId=null、liveState=IDLE")
    void unassignedTaskYieldsIdleNull() {
        User owner = newUser(0);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        var task = taskService.createTask(twoWaypointTask());

        TaskLiveDevice resolved = liveDeviceResolver.resolveForTask(task.getId());

        assertThat(resolved.deviceId()).isNull();
        assertThat(resolved.liveState()).isEqualTo("IDLE");
    }

    @Test
    @DisplayName("TaskVo 接线：/task/detail 返回 deviceId 与 liveState")
    void taskDetailCarriesDeviceMapping() throws Exception {
        User owner = newUser(0);
        User rider = newUser(1);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        var task = taskService.createTask(twoWaypointTask());
        String deviceId = "ldv-vo-" + rid;
        bindRider(rider.getId(), deviceId);
        UserContext.setUser(rider.getId(), rider.getUserName(), 1);
        markPaid(task);
        taskService.acceptTask(task.getTaskNum(), rider.getId());
        appWebSocketService.requestConnection(deviceId);
        appWebSocketService.markAsConnected(deviceId);

        var mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        mockMvc.perform(get("/task/detail")
                        .param("taskNum", task.getTaskNum())
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                owner.getId(), owner.getUserName(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value(deviceId))
                .andExpect(jsonPath("$.data.liveState").value("IDLE"));
    }

    // ---------- helpers ----------

    private User newUser(int role) {
        User user = new User();
        user.setUserName("ldv" + rid + "-" + role);
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
    }

    private void markPaid(Task task) {
        MissionOrder order = orderRepository.findByTaskId(task.getId()).orElseThrow();
        order.setOrderStatus(OrderStatus.PAID);
        orderRepository.save(order);
    }

    private void bindRider(Long riderId, String deviceId) {
        RiderUav binding = new RiderUav();
        binding.setUserId(riderId);
        binding.setDjiId(deviceId);
        riderUavRepository.save(binding);
    }

    private TaskDto twoWaypointTask() {
        TaskDto dto = new TaskDto();
        dto.setTaskName("ldv-task-" + rid);
        dto.setType(TaskType.SURVEY);
        WaypointDto a = new WaypointDto();
        a.setOrderIndex(0);
        a.setLongitude(121.0);
        a.setLatitude(31.0);
        a.setAltitude(100.0);
        WaypointDto b = new WaypointDto();
        b.setOrderIndex(1);
        b.setLongitude(121.01);
        b.setLatitude(31.0);
        b.setAltitude(100.0);
        dto.setWaypoints(List.of(a, b));
        return dto;
    }
}
