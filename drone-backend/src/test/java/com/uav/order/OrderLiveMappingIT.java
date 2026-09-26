package com.uav.order;

import com.uav.live.service.AppWebSocketService;
import com.uav.order.mapper.OrderRepository;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.pojo.entity.Task;
import com.uav.task.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1B-4b 微任务：/order/detail 的 OrderVO 携带 deviceId + liveState（任务→设备映射接线）。
 * 设备在线 → deviceId=绑定设备；离线/未接单 → deviceId=null 且 liveState=IDLE。
 *
 * <p>层次与驱动（O6/R2/R4/R9）：进程内 MockMvc 集成测试，继承 {@link IntegrationTestBase}
 * （{@code MOCK} + 基类注入的 MockMvc + {@code @Transactional}），<b>不是</b>端到端测试——
 * 不发真实 HTTP、不起真实容器；不再用 {@code MockMvcBuilders.webAppContextSetup} 手工搭建。
 *
 * <p>身份来源（R3）：所有者与飞手均通过 {@link TestAccounts} 走真实
 * {@code /user/register} + {@code /user/login}、{@code /rider/register}（带 djiId 同时完成绑定），
 * 令牌由服务端签发，不再自签 JWT 绕过认证链路（R3）。
 *
 * <p>隔离与造数（R5/R7/R8）：事务回滚隔离；任务 DTO 与订单状态迁移走共享工厂
 * （{@code fixtures.twoWaypointTask()}、{@code fixtures.awaitingRiderConfirm(task, rider.id())}）；
 * ThreadLocal 由基类 {@code @AfterEach} 统一清理，本类不再手工 {@code UserContext.clear()}。
 */
class OrderLiveMappingIT extends IntegrationTestBase {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private AppWebSocketService appWebSocketService;

    @Autowired
    private TaskService taskService;

    @Test
    @DisplayName("/order/detail 返回 deviceId 与 liveState（接单+设备在线场景）")
    void orderDetailCarriesDeviceMapping() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        String deviceId = UniqueNames.djiId();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("odm-rider"), deviceId);

        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        Task task = taskService.createTask(fixtures.twoWaypointTask());

        UserContext.setUser(rider.id(), rider.userName(), rider.role());
        fixtures.awaitingRiderConfirm(task, rider.id());
        taskService.riderConfirmOrder(task.getTaskNum(), rider.id());
        appWebSocketService.requestConnection(deviceId);
        appWebSocketService.markAsConnected(deviceId);

        String orderNum = orderRepository.findByTaskId(task.getId()).orElseThrow().getOrderNum();

        mockMvc.perform(get("/order/detail")
                        .param("orderNum", orderNum)
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value(deviceId))
                .andExpect(jsonPath("$.data.liveState").value("IDLE"));
    }

    @Test
    @DisplayName("/order/detail 设备离线 → deviceId=null、liveState=IDLE")
    void orderDetailOfflineYieldsNullDeviceId() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        String deviceId = UniqueNames.djiId();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("odm-rider"), deviceId);

        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        Task task = taskService.createTask(fixtures.twoWaypointTask());

        UserContext.setUser(rider.id(), rider.userName(), rider.role());
        fixtures.awaitingRiderConfirm(task, rider.id());
        taskService.riderConfirmOrder(task.getTaskNum(), rider.id());

        String orderNum = orderRepository.findByTaskId(task.getId()).orElseThrow().getOrderNum();
        mockMvc.perform(get("/order/detail")
                        .param("orderNum", orderNum)
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value(nullValue()))
                .andExpect(jsonPath("$.data.liveState").value("IDLE"));
    }
}
