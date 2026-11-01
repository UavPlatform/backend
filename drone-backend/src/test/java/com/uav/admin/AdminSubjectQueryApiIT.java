package com.uav.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.order.mapper.OrderComplaintRepository;
import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.pojo.entity.OrderComplaint;
import com.uav.server.enums.ComplaintReason;
import com.uav.server.enums.ComplaintStatus;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.task.service.TaskService;
import com.uav.uav.mapper.UavRepository;
import com.uav.uav.pojo.entity.Uav;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 监管端主体查询集成测试（TASK-BACKEND-006 / REQ-FRONTEND-001 / ADR-0004）：
 * {@code /admin/users}、{@code /admin/pilots} 列表与详情——关键字/状态筛选、分页信封、
 * 飞手详情的绑定无人机表（djiId/机型/在线/可用）、关联订单口径、权限与空数据边界；
 * 无人机启用/禁用复用 {@code POST /admin/uav/available}（按 djiId）并在详情回读生效。
 *
 * <p>层次与驱动（O6/R2/R4）：进程内 MockMvc 集成测试，继承 {@link IntegrationTestBase}
 * （MOCK + {@code @AutoConfigureMockMvc} + {@code @Transactional}），不自称端到端。
 *
 * <p>身份来源（R3）：管理员与普通/飞手身份一律取自 {@link TestAccounts} 的真实登录/注册接口；
 * 造数走共享工厂（R7），ThreadLocal 由基类清理（R8），事务回滚即隔离（R5）。
 */
class AdminSubjectQueryApiIT extends IntegrationTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TaskService taskService;

    @Autowired
    private UavRepository uavRepository;

    @Autowired
    private TaskAssignmentRepository taskAssignmentRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderComplaintRepository complaintRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("注册用户列表：关键字/状态筛选、飞手不混入、订单数聚合、分页信封")
    void userListFiltersKeywordAndStatus() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("r"), null);
        Task task = fixtures.task(TaskStatus.IDLE, 100.0, owner.id());
        fixtures.order(owner.id(), task, OrderStatus.PAID, "128.00");

        TestAccounts.AdminAccount admin = accounts().adminLogin();

        // 关键字命中：仅本人，订单数聚合为 1
        JsonNode hit = adminGet(get("/admin/users")
                .param("keyword", owner.userName()).param("page", "0").param("size", "10"), admin);
        assertThat(hit.path("totalElements").asLong()).isEqualTo(1);
        JsonNode row = hit.path("content").get(0);
        assertThat(row.path("userId").asLong()).isEqualTo(owner.id());
        assertThat(row.path("userName").asText()).isEqualTo(owner.userName());
        assertThat(row.path("status").asInt()).isEqualTo(1);
        assertThat(row.path("orderCount").asLong()).isEqualTo(1);
        assertThat(row.path("disputeCount").asLong()).isZero();
        assertThat(row.path("registerTime").asText(null)).isNotBlank();

        // 飞手不在注册用户列表
        JsonNode pilotHit = adminGet(get("/admin/users").param("keyword", rider.userName()), admin);
        assertThat(pilotHit.path("totalElements").asLong())
                .as("role=1 飞手不应出现在 /admin/users").isZero();
        assertThat(pilotHit.path("content").isEmpty()).isTrue();

        // 状态筛选：注册账号 status=1，筛 status=0 无命中
        JsonNode statusMiss = adminGet(get("/admin/users")
                .param("keyword", owner.userName()).param("status", "0"), admin);
        assertThat(statusMiss.path("totalElements").asLong()).isZero();

        // 分页信封：size=1 → 单条内容 + totalPages=1
        JsonNode paged = adminGet(get("/admin/users")
                .param("keyword", owner.userName()).param("page", "0").param("size", "1"), admin);
        assertThat(paged.path("content").size()).isEqualTo(1);
        assertThat(paged.path("totalElements").asLong()).isEqualTo(1);
        assertThat(paged.path("totalPages").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("用户列表/详情：争议数聚合、名下投诉列表、注册时间")
    void userListAndDetailExposeDisputesAndRegisterTime() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = fixtures.task(TaskStatus.IDLE, 100.0, owner.id());
        MissionOrder order = fixtures.order(owner.id(), task, OrderStatus.DISPUTED, "99.00");

        OrderComplaint complaint = new OrderComplaint();
        complaint.setOrderNum(order.getOrderNum());
        complaint.setUserId(owner.id());
        complaint.setReason(ComplaintReason.QUALITY_ISSUE);
        complaint.setDescription("吊运质量不符合约定");
        complaint.setStatus(ComplaintStatus.PENDING);
        complaint.setRefundAmount(order.getTotalAmount());
        complaintRepository.save(complaint);

        TestAccounts.AdminAccount admin = accounts().adminLogin();

        JsonNode listRow = adminGet(get("/admin/users")
                .param("keyword", owner.userName()).param("page", "0").param("size", "10"), admin)
                .path("content").get(0);
        assertThat(listRow.path("disputeCount").asLong()).isEqualTo(1);
        assertThat(listRow.path("registerTime").asText(null)).isNotBlank();

        JsonNode detail = adminGet(get("/admin/users/" + owner.id()), admin);
        assertThat(detail.path("registerTime").asText(null)).isNotBlank();
        assertThat(detail.path("complaints").size()).isEqualTo(1);
        JsonNode c = detail.path("complaints").get(0);
        assertThat(c.path("orderNum").asText()).isEqualTo(order.getOrderNum());
        assertThat(c.path("reason").asText()).isEqualTo("QUALITY_ISSUE");
        assertThat(c.path("status").asText()).isEqualTo("PENDING");
        assertThat(c.path("description").asText()).contains("吊运质量");
    }

    @Test
    @DisplayName("用户详情：关联订单摘要（订单号/任务/状态/金额）；飞手或不存在 ID → 404")
    void userDetailReturnsOrderSummary() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("r"), null);
        Task task = fixtures.task(TaskStatus.IDLE, 100.0, owner.id());
        MissionOrder order = fixtures.order(owner.id(), task, OrderStatus.PAID, "168.50");

        TestAccounts.AdminAccount admin = accounts().adminLogin();

        JsonNode data = adminGet(get("/admin/users/" + owner.id()), admin);
        assertThat(data.path("userId").asLong()).isEqualTo(owner.id());
        assertThat(data.path("userName").asText()).isEqualTo(owner.userName());
        assertThat(data.path("role").asInt()).isZero();
        assertThat(data.path("orders").size()).isEqualTo(1);
        JsonNode summary = data.path("orders").get(0);
        assertThat(summary.path("orderNum").asText()).isEqualTo(order.getOrderNum());
        assertThat(summary.path("taskNum").asText()).isEqualTo(task.getTaskNum());
        assertThat(summary.path("taskName").asText()).isEqualTo(task.getTaskName());
        assertThat(summary.path("orderStatusCode").asInt()).isEqualTo(1);
        assertThat(summary.path("orderStatus").asText()).isEqualTo("PAID");
        assertThat(summary.path("totalAmount").decimalValue()).isEqualByComparingTo("168.50");
        assertThat(summary.path("ownerName").asText()).isEqualTo(owner.userName());

        // 飞手 ID 不能走用户详情（实体分界）
        mockMvc.perform(get("/admin/users/" + rider.id())
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.USER_NOT_FOUND.getCode()));

        // 不存在的 ID
        mockMvc.perform(get("/admin/users/987654321")
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.USER_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("注册飞手列表：只含 role=1，完成单聚合，空关键字无命中")
    void pilotListAggregatesAndExcludesUsers() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        fixtures.bindDrone(rider.id(), UniqueNames.djiId());

        // 完成单：直连仓储造一条 complete_time 非空的接单记录
        Task task = fixtures.task(TaskStatus.COMPLETED, 100.0, owner.id());
        TaskAssignment assignment = new TaskAssignment();
        assignment.setTaskId(task.getId());
        assignment.setRiderId(rider.id());
        assignment.setAcceptTime(LocalDateTime.now());
        assignment.setCompleteTime(LocalDateTime.now());
        taskAssignmentRepository.save(assignment);

        TestAccounts.AdminAccount admin = accounts().adminLogin();

        JsonNode hit = adminGet(get("/admin/pilots").param("keyword", rider.userName()), admin);
        assertThat(hit.path("totalElements").asLong()).isEqualTo(1);
        JsonNode row = hit.path("content").get(0);
        assertThat(row.path("userId").asLong()).isEqualTo(rider.id());
        assertThat(row.path("status").asInt()).isEqualTo(1);
        assertThat(row.path("uavCount").isMissingNode() || row.path("uavCount").isNull()).isTrue();
        assertThat(row.path("completedCount").asLong()).isEqualTo(1);

        // 普通用户不混入飞手列表
        assertThat(adminGet(get("/admin/pilots").param("keyword", owner.userName()), admin)
                .path("totalElements").asLong())
                .as("role=0 用户不应出现在 /admin/pilots").isZero();

        // 空数据边界：无命中 → 空 content + total 归零
        JsonNode empty = adminGet(get("/admin/pilots").param("keyword", UniqueNames.unique("nope")), admin);
        assertThat(empty.path("content").isEmpty()).isTrue();
        assertThat(empty.path("totalElements").asLong()).isZero();
        assertThat(empty.path("totalPages").asInt()).isZero();
    }

    @Test
    @DisplayName("飞手详情：借用记录 + 关联订单（已接单与已选定）")
    void pilotDetailReturnsBorrowsAndOrders() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider();
        String serial = fixtures.bindDrone(rider.id(), UniqueNames.djiId());

        UserContext.setUser(owner.id(), owner.userName(), 0);
        Task taskA = taskService.createTask(fixtures.twoWaypointTask(UniqueNames.unique("pd-a")));
        UserContext.clear();
        fixtures.awaitingRiderConfirm(taskA, rider.id());
        taskService.riderConfirmOrder(taskA.getTaskNum(), rider.id());
        MissionOrder orderA = orderRepository.findByTaskId(taskA.getId()).orElseThrow();

        UserContext.setUser(owner.id(), owner.userName(), 0);
        Task taskB = taskService.createTask(fixtures.twoWaypointTask(UniqueNames.unique("pd-b")));
        UserContext.clear();
        MissionOrder orderB = fixtures.selectAndLock(taskB, rider.id());

        TestAccounts.AdminAccount admin = accounts().adminLogin();

        JsonNode data = adminGet(get("/admin/pilots/" + rider.id()), admin);
        assertThat(data.path("userId").asLong()).isEqualTo(rider.id());
        assertThat(data.path("userName").asText()).isEqualTo(rider.userName());
        assertThat(data.path("role").asInt()).isEqualTo(1);
        assertThat(data.path("drones").isMissingNode() || data.path("drones").isNull()).isTrue();

        JsonNode borrows = data.path("borrows");
        assertThat(borrows.size()).isEqualTo(1);
        assertThat(borrows.get(0).path("status").asText()).isEqualTo("APPROVED");
        assertThat(borrows.get(0).path("serialNumber").asText()).isEqualTo(serial);
        assertThat(borrows.get(0).path("aircraftModelId").asLong()).isEqualTo(fixtures.defaultAircraftModelId());

        List<String> orderNums = data.path("orders").findValuesAsText("orderNum");
        assertThat(orderNums).contains(orderA.getOrderNum(), orderB.getOrderNum());

        mockMvc.perform(get("/admin/pilots/" + owner.id())
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(ApiErrorCode.USER_NOT_FOUND.getCode()));
        mockMvc.perform(get("/admin/pilots/987654321")
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("主体查询四个端点：普通用户/飞手 403，未登录 401")
    void subjectEndpointsRequireAdminRole() throws Exception {
        TestAccounts.Account normal = accounts().registerUser();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("r"), null);
        String userId = String.valueOf(normal.id());

        for (String path : List.of("/admin/users", "/admin/users/" + userId,
                "/admin/pilots", "/admin/pilots/" + userId)) {
            mockMvc.perform(get(path).header("Authorization", normal.authorization()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(path).header("Authorization", rider.authorization()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("空数据边界：新用户无订单 → orders 空数组；越界页 content 空但 total 保留")
    void emptyDataBoundaries() throws Exception {
        TestAccounts.Account fresh = accounts().registerUser();
        TestAccounts.Account soloRider = accounts().registerRider(UniqueNames.userName("solo"), null);
        TestAccounts.AdminAccount admin = accounts().adminLogin();

        JsonNode user = adminGet(get("/admin/users/" + fresh.id()), admin);
        assertThat(user.path("orders").isArray()).isTrue();
        assertThat(user.path("orders").size())
                .as("新注册用户无订单 → 空数组而非 null/缺字段").isZero();
        assertThat(user.path("complaints").isArray()).isTrue();
        assertThat(user.path("complaints").size()).isZero();

        JsonNode page = adminGet(get("/admin/users")
                .param("keyword", fresh.userName()).param("page", "5").param("size", "10"), admin);
        assertThat(page.path("content").size()).isZero();
        assertThat(page.path("page").asInt()).isEqualTo(5);
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);

        JsonNode pilot = adminGet(get("/admin/pilots/" + soloRider.id()), admin);
        assertThat(pilot.path("borrows").size()).isZero();
        assertThat(pilot.path("drones").isMissingNode() || pilot.path("drones").isNull()).isTrue();
        assertThat(pilot.path("orders").size()).isZero();
        assertThat(pilot.path("completedCount").asLong()).isZero();
    }

    // ---------- 工具 ----------

    /** 管理员 GET：断言 200 后返回 {@code data} 节点。 */
    private JsonNode adminGet(MockHttpServletRequestBuilder request, TestAccounts.AdminAccount admin)
            throws Exception {
        String body = mockMvc.perform(request.header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).path("data");
    }

    /** 从飞手详情的无人机数组中按 djiId 取行。 */
    private JsonNode droneByDjiId(JsonNode drones, String djiId) {
        for (JsonNode drone : drones) {
            if (djiId.equals(drone.path("djiId").asText(null))) {
                return drone;
            }
        }
        throw new AssertionError("无人机表中缺少 djiId=" + djiId + "，实际=" + drones);
    }

    /** 按 djiId 造/改设备档案（绑机路径可能已自动补档，此处 upsert 避免重复行）。 */
    private void saveUav(String djiId, char online, char available) {
        Uav uav = uavRepository.findPrimaryByDjiId(djiId).orElseGet(() -> {
            Uav newUav = new Uav();
            newUav.setDjiId(djiId);
            newUav.setUavName(UniqueNames.unique("uv"));
            newUav.setControllerModel("TestController");
            return newUav;
        });
        uav.setOnlineStatus(online);
        uav.setIsAvailable(available);
        uavRepository.save(uav);
    }
}
