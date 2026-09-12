package com.uav.order;

import com.uav.live.service.AppWebSocketService;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1B-4b 微任务：/order/detail 的 OrderVO 携带 deviceId + liveState（任务→设备映射接线）。
 * 设备在线 → deviceId=绑定设备；离线/未接单 → deviceId=null 且 liveState=IDLE。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderLiveMappingTest {

    @Autowired
    WebApplicationContext wac;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RiderUavRepository riderUavRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    AppWebSocketService appWebSocketService;

    @Autowired
    TaskService taskService;

    MockMvc mockMvc;

    private long rid;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        rid = System.nanoTime();
    }

    @AfterEach
    void restoreContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("/order/detail 返回 deviceId 与 liveState（接单+设备在线场景）")
    void orderDetailCarriesDeviceMapping() throws Exception {
        User owner = newUser(0);
        User rider = newUser(1);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        Task task = taskService.createTask(twoWaypointTask());
        String deviceId = "odm-dev-" + rid;
        bindRider(rider.getId(), deviceId);
        UserContext.setUser(rider.getId(), rider.getUserName(), 1);
        markPaid(task);
        taskService.acceptTask(task.getTaskNum(), rider.getId());
        appWebSocketService.requestConnection(deviceId);
        appWebSocketService.markAsConnected(deviceId);

        String orderNum = orderNumOf(task);

        mockMvc.perform(get("/order/detail")
                        .param("orderNum", orderNum)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                owner.getId(), owner.getUserName(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value(deviceId))
                .andExpect(jsonPath("$.data.liveState").value("IDLE"));
    }

    @Test
    @DisplayName("/order/detail 设备离线 → deviceId=null、liveState=IDLE")
    void orderDetailOfflineYieldsNullDeviceId() throws Exception {
        User owner = newUser(0);
        User rider = newUser(1);
        UserContext.setUser(owner.getId(), owner.getUserName(), 0);
        Task task = taskService.createTask(twoWaypointTask());
        String deviceId = "odm-off-" + rid;
        bindRider(rider.getId(), deviceId);
        UserContext.setUser(rider.getId(), rider.getUserName(), 1);
        markPaid(task);
        taskService.acceptTask(task.getTaskNum(), rider.getId());

        String orderNum = orderNumOf(task);
        mockMvc.perform(get("/order/detail")
                        .param("orderNum", orderNum)
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                owner.getId(), owner.getUserName(), 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value(nullValue()))
                .andExpect(jsonPath("$.data.liveState").value("IDLE"));
    }

    // ---------- helpers ----------

    private void markPaid(Task task) {
        MissionOrder order = orderRepository.findByTaskId(task.getId()).orElseThrow();
        order.setOrderStatus(OrderStatus.PAID);
        orderRepository.save(order);
    }

    private String orderNumOf(Task task) {
        return orderRepository.findByTaskId(task.getId()).orElseThrow().getOrderNum();
    }

    private User newUser(int role) {
        User user = new User();
        user.setUserName("odm" + rid + "-" + role);
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
    }

    private void bindRider(Long riderId, String deviceId) {
        RiderUav binding = new RiderUav();
        binding.setUserId(riderId);
        binding.setDjiId(deviceId);
        riderUavRepository.save(binding);
    }

    private TaskDto twoWaypointTask() {
        TaskDto dto = new TaskDto();
        dto.setTaskName("odm-task-" + rid);
        dto.setType(com.uav.server.enums.TaskType.SURVEY);
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
