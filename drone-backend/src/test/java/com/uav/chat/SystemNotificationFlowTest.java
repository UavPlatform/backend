package com.uav.chat;

import com.uav.chat.pojo.entity.ChatEnvelope;
import com.uav.chat.pojo.enums.MsgType;
import com.uav.chat.service.MessageService;
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
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1B-3 端到端测试（裁决 Q9=A）：六类状态变更点向对方系统通知会话插入结构化系统消息；
 * 在线经聊天 WS 实时推送，离线由既有 /chat/Message/sync 补偿。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SystemNotificationFlowTest {

    @LocalServerPort
    int port;

    @Autowired
    org.springframework.web.context.WebApplicationContext wac;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    TaskService taskService;

    @Autowired
    MessageService messageService;

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
    @DisplayName("① 支付成功：订单 PENDING→PAID → 所有者收到 ORDER_PAID 系统消息")
    void paymentSuccessNotifiesOwner() {
        User owner = newUser(0);
        Task task = createTask(owner);
        markPaid(task);

        List<ChatEnvelope> unread = messageService.getUnreadMessages(owner.getId());
        Optional<ChatEnvelope> hit = findByName(unread, "ORDER_PAID");
        assertThat(hit).isPresent();
        assertThat(hit.orElseThrow().getMsgType()).isEqualTo(MsgType.ORDER);
        assertThat(hit.orElseThrow().getPayload().get("type")).isEqualTo("ORDER");
        assertThat(hit.orElseThrow().getPayload().get("data")).isNotNull();
    }

    @Test
    @DisplayName("② 接单：IDLE→IN_PROGRESS → 所有者收到 TASK_ACCEPTED（含 riderId）")
    void acceptNotifiesOwner() {
        User owner = newUser(0);
        User rider = newUser(1);
        Task task = createPaidTask(owner);
        UserContext.setUser(rider.getId(), rider.getUserName(), 1); // 接单 actor = 飞手
        taskService.acceptTask(task.getTaskNum(), rider.getId());

        List<ChatEnvelope> unread = messageService.getUnreadMessages(owner.getId());
        Optional<ChatEnvelope> hit = findByName(unread, "TASK_ACCEPTED");
        assertThat(hit).isPresent();
        assertThat(hit.orElseThrow().getMsgType()).isEqualTo(MsgType.NOTICE);
        assertThat(hit.orElseThrow().getPayload().get("data"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("riderId", rider.getId().intValue());
    }

    @Test
    @DisplayName("③ 取消：IN_PROGRESS→IDLE → 所有者收到 TASK_CANCELLED")
    void cancelNotifiesOwner() {
        User owner = newUser(0);
        User rider = newUser(1);
        Task task = createPaidTask(owner);
        UserContext.setUser(rider.getId(), rider.getUserName(), 1);
        taskService.acceptTask(task.getTaskNum(), rider.getId());
        taskService.riderCancelTask(task.getTaskNum(), rider.getId());

        List<ChatEnvelope> unread = messageService.getUnreadMessages(owner.getId());
        assertThat(findByName(unread, "TASK_CANCELLED")).isPresent();
        assertThat(findByName(unread, "TASK_ACCEPTED")).isPresent(); // 接单通知同样在流中
    }

    @Test
    @DisplayName("④⑤ 完成+待验收：IN_PROGRESS→COMPLETED 与订单→WAITING_CONFIRM 各产生一条通知")
    void completionProducesTaskAndOrderNotifications() {
        User owner = newUser(0);
        User rider = newUser(1);
        Task task = createPaidTask(owner);
        UserContext.setUser(rider.getId(), rider.getUserName(), 1);
        taskService.acceptTask(task.getTaskNum(), rider.getId());
        taskService.riderCompleteTask(task.getTaskNum(), rider.getId(), null);

        List<ChatEnvelope> unread = messageService.getUnreadMessages(owner.getId());
        assertThat(findByName(unread, "TASK_COMPLETED")).isPresent();
        assertThat(findByName(unread, "ORDER_WAITING_CONFIRM")).isPresent();
    }

    @Test
    @DisplayName("⑥ 确认完成：订单 WAITING_CONFIRM→COMPLETED → 接单飞手收到 ORDER_CONFIRMED")
    void confirmNotifiesRider() {
        User owner = newUser(0);
        User rider = newUser(1);
        Task task = createPaidTask(owner);
        taskService.acceptTask(task.getTaskNum(), rider.getId());
        taskService.riderCompleteTask(task.getTaskNum(), rider.getId(), null);
        taskService.userConfirmTask(task.getTaskNum(), owner.getId());

        List<ChatEnvelope> riderUnread = messageService.getUnreadMessages(rider.getId());
        Optional<ChatEnvelope> hit = findByName(riderUnread, "ORDER_CONFIRMED");
        assertThat(hit).isPresent();
        assertThat(hit.orElseThrow().getMsgType()).isEqualTo(MsgType.ORDER);
    }

    @Test
    @DisplayName("离线补偿：未连接 WS 时经 /chat/Message/sync 可拉到结构化系统消息")
    void offlineCompensationViaSyncEndpoint() throws Exception {
        User owner = newUser(0);
        Task task = createTask(owner);
        markPaid(task);

        var mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(wac).build();
        var response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/chat/Message/sync")
                        .header("Authorization", "Bearer " + jwtUtil.generateToken(
                                owner.getId(), owner.getUserName(), owner.getRole())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("ORDER_PAID");
        assertThat(response).contains("\"type\":\"ORDER\"");
    }

    @Test
    @DisplayName("实时推送：接收方在线时状态变更经聊天 WS 推送结构化信封（msgType=2）")
    void realtimePushToConnectedRecipient() throws Exception {
        User owner = newUser(0);
        Task task = createTask(owner);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        OwnerInboxEndpoint endpoint = new OwnerInboxEndpoint();
        Session session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(),
                URI.create("ws://localhost:" + port + "/ws/" + owner.getId() + "?token="
                        + jwtUtil.generateToken(owner.getId(), owner.getUserName(), 0)));
        try {
            assertThat(endpoint.opened.await(10, TimeUnit.SECONDS)).isTrue();
            markPaid(task);

            assertThat(endpoint.orderPaidLatch.await(5, TimeUnit.SECONDS)).isTrue();
            String payloadMsg = endpoint.firstOrderPaidPayload();
            assertThat(payloadMsg).contains("ORDER_PAID");
            assertThat(payloadMsg).contains("\"type\":\"ORDER\"");
        } finally {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- helpers ----------

    private User newUser(int role) {
        User user = new User();
        user.setUserName("ntf" + rid + "-" + role + "-" + UUID.randomUUID().toString().substring(0, 6));
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
    }

    private Task createTask(User owner) {
        UserContext.setUser(owner.getId(), owner.getUserName(), owner.getRole());
        TaskDto dto = new TaskDto();
        dto.setTaskName("ntf-task-" + rid);
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
        Task saved = taskService.createTask(dto);
        return saved;
    }

    private void markPaid(Task task) {
        MissionOrder order = orderRepository.findByTaskId(task.getId()).orElseThrow();
        order.setOrderStatus(OrderStatus.PAID);
        orderRepository.save(order);
    }

    private Task createPaidTask(User owner) {
        Task task = createTask(owner);
        markPaid(task);
        return task;
    }

    private Optional<ChatEnvelope> findByName(List<ChatEnvelope> unread, String name) {
        return unread.stream()
                .filter(e -> e.getPayload() != null && name.equals(e.getPayload().get("name")))
                .findFirst();
    }

    /** 接收方模拟：收集下行信封，对含 ORDER_PAID 的消息放行闩锁。 */
    static class OwnerInboxEndpoint extends Endpoint {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch orderPaidLatch = new CountDownLatch(1);
        final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();
        final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
        private volatile String firstOrderPaidPayload;

        String firstOrderPaidPayload() {
            return firstOrderPaidPayload;
        }

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            sessions.add(session);
            // Tomcat 通过反射读取泛型参数，必须用匿名类（lambda 会抛 IllegalStateException）
            session.addMessageHandler(new MessageHandler.Whole<String>() {
                @Override
                public void onMessage(String message) {
                    messages.add(message);
                    if (message.contains("ORDER_PAID")) {
                        if (firstOrderPaidPayload == null) {
                            firstOrderPaidPayload = message;
                        }
                        orderPaidLatch.countDown();
                    }
                }
            });
            opened.countDown();
        }
    }
}
