package com.uav.chat;

import com.uav.chat.pojo.entity.ChatEnvelope;
import com.uav.chat.pojo.enums.MsgType;
import com.uav.chat.service.MessageService;
import com.uav.server.enums.CargoCategory;
import com.uav.server.enums.TaskType;
import com.uav.server.util.UserContext;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.service.TaskApplicationService;
import com.uav.task.service.TaskService;
import com.uav.user.pojo.entity.User;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端协议测试（1B-3 / 裁决 Q9=A / TASK-BACKEND-004 冲突点 4）：撮合与订单关键状态变更点
 * 向对方系统通知会话插入结构化系统消息；在线经聊天 WS 实时推送，离线由既有
 * {@code /chat/Message/sync} 补偿。
 *
 * <p>TASK-BACKEND-004 新增覆盖的事件：
 * {@code APPLICATION_RECEIVED}（应征，事务提交后派发）、{@code ORDER_SELECTED}（用户选定）、
 * {@code ORDER_PAID} + {@code ORDER_WAIT_RIDER_CONFIRM}（支付成功/待飞手确认）、
 * {@code TASK_ACCEPTED} + {@code MATCH_CONFIRMED}（双确认完成）。
 *
 * <p>真实驱动方式（R9/O4）：在线推送断言依赖真实 WS 握手与跨线程投递，离线补偿走真实 TCP
 * {@code /chat/Message/sync}，因此继承 {@link RealProtocolTestBase}（{@code RANDOM_PORT}）。
 *
 * <p><b>R5 例外且强制：本类不得使用 {@code @Transactional}。</b>系统通知经
 * {@code TransactionSynchronization.afterCommit()} 派发（见 {@code SystemNotifyInterceptor} 与
 * {@code TaskApplicationServiceImpl}），回滚事务中 {@code afterCommit()} 永不执行，
 * 若加 {@code @Transactional} 这些断言会静默失去覆盖。
 *
 * <p>R5 隔离方式：继承的基类不含事务，数据真实提交；唯一性由 {@link TestAccounts}（唯一用户名/身份）
 * 与共享工厂（唯一任务名）保证，<b>不再以 {@code System.nanoTime()} 兜底</b>；
 * 每类用例自行关闭建立的 WS 会话。
 *
 * <p>R3：全部身份和 token 均来自 {@link TestAccounts} 真实注册/登录。
 */
class SystemNotificationE2EIT extends RealProtocolTestBase {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskApplicationService taskApplicationService;

    @Autowired
    private MessageService messageService;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    @DisplayName("① 支付成功：订单 PENDING→PAID → 所有者收到 ORDER_PAID；飞手收到 ORDER_WAIT_RIDER_CONFIRM")
    void paymentSuccessNotifiesOwnerAndRider() {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = createTask(owner);
        long riderId = fixtures.rider().getId();
        fixtures.selectAndLock(task, riderId);
        fixtures.payLockedOrder(task); // 生产 handleNotify：PENDING→PAID + 撮合状态推进

        List<ChatEnvelope> unread = messageService.getUnreadMessages(owner.id());
        Optional<ChatEnvelope> hit = findByName(unread, "ORDER_PAID");
        assertThat(hit).isPresent();
        assertThat(hit.orElseThrow().getMsgType()).isEqualTo(MsgType.ORDER);
        assertThat(hit.orElseThrow().getPayload().get("type")).isEqualTo("ORDER");
        assertThat(hit.orElseThrow().getPayload().get("data")).isNotNull();

        // 冲突点 4：支付成功 → 被选定飞手收到「待确认」通知
        assertThat(findByName(messageService.getUnreadMessages(riderId), "ORDER_WAIT_RIDER_CONFIRM"))
                .isPresent();
    }

    @Test
    @DisplayName("② 双确认：飞手 confirm-order → 所有者收到 TASK_ACCEPTED，飞手收到 MATCH_CONFIRMED")
    void confirmNotifiesBothParties() {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = createTask(owner);
        User rider = fixtures.rider();
        long riderId = rider.getId();
        fixtures.awaitingRiderConfirm(task, riderId);
        UserContext.setUser(rider.getId(), rider.getUserName(), rider.getRole()); // 确认 actor = 飞手
        taskService.riderConfirmOrder(task.getTaskNum(), riderId);

        List<ChatEnvelope> unread = messageService.getUnreadMessages(owner.id());
        Optional<ChatEnvelope> hit = findByName(unread, "TASK_ACCEPTED");
        assertThat(hit).isPresent();
        assertThat(hit.orElseThrow().getPayload().get("data"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("riderId", (int) riderId);

        assertThat(findByName(messageService.getUnreadMessages(riderId), "MATCH_CONFIRMED"))
                .isPresent();
    }

    @Test
    @DisplayName("③ 取消：执行中取消 → 所有者收到 TASK_CANCELLED（双确认通知同流中）")
    void cancelNotifiesOwner() {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = createTask(owner);
        long riderId = fixtures.rider().getId();
        fixtures.awaitingRiderConfirm(task, riderId);
        taskService.riderConfirmOrder(task.getTaskNum(), riderId);
        taskService.riderCancelTask(task.getTaskNum(), riderId);

        List<ChatEnvelope> unread = messageService.getUnreadMessages(owner.id());
        assertThat(findByName(unread, "TASK_CANCELLED")).isPresent();
        assertThat(findByName(unread, "TASK_ACCEPTED")).isPresent(); // 双确认通知同样在流中
    }

    @Test
    @DisplayName("④⑤ 完成+待验收：交付（含履约证据）→ 所有者收到 TASK_COMPLETED 与 ORDER_WAITING_CONFIRM")
    void completionProducesTaskAndOrderNotifications() {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = createTask(owner);
        long riderId = fixtures.rider().getId();
        fixtures.awaitingRiderConfirm(task, riderId);
        taskService.riderConfirmOrder(task.getTaskNum(), riderId);
        fixtures.evidence(task.getTaskNum(), riderId);
        taskService.riderCompleteTask(task.getTaskNum(), riderId, null);

        List<ChatEnvelope> unread = messageService.getUnreadMessages(owner.id());
        assertThat(findByName(unread, "TASK_COMPLETED")).isPresent();
        assertThat(findByName(unread, "ORDER_WAITING_CONFIRM")).isPresent();
    }

    @Test
    @DisplayName("⑥ 确认完成：订单 WAITING_CONFIRM→COMPLETED → 接单飞手收到 ORDER_CONFIRMED")
    void confirmNotifiesRider() {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = createTask(owner);
        long riderId = fixtures.rider().getId();
        fixtures.awaitingRiderConfirm(task, riderId);
        taskService.riderConfirmOrder(task.getTaskNum(), riderId);
        fixtures.evidence(task.getTaskNum(), riderId);
        taskService.riderCompleteTask(task.getTaskNum(), riderId, null);
        taskService.userConfirmTask(task.getTaskNum(), owner.id());

        List<ChatEnvelope> riderUnread = messageService.getUnreadMessages(riderId);
        Optional<ChatEnvelope> hit = findByName(riderUnread, "ORDER_CONFIRMED");
        assertThat(hit).isPresent();
        assertThat(hit.orElseThrow().getMsgType()).isEqualTo(MsgType.ORDER);
    }

    @Test
    @DisplayName("⑦ 应征/选定：apply → 所有者收到 APPLICATION_RECEIVED；select-rider → 飞手收到 ORDER_SELECTED")
    void applyAndSelectNotifyCounterpart() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = createTransportTask(owner);
        // 飞手：注册（不带设备）+ 生产绑定路径映射机型 FC30
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("rider"), null);
        fixtures.bindDrone(rider.id(), UniqueNames.djiId(), fixtures.defaultAircraftModelId());

        var applied = taskApplicationService.apply(task.getTaskNum(), rider.id(),
                fixtures.defaultAircraftModelId(), null);
        assertThat(findByName(messageService.getUnreadMessages(owner.id()), "APPLICATION_RECEIVED"))
                .as("应征后属主应收到 APPLICATION_RECEIVED")
                .isPresent();

        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        taskService.selectRider(task.getTaskNum(), owner.id(), applied.getApplicationId(),
                LocalDateTime.now().plusDays(1));
        assertThat(findByName(messageService.getUnreadMessages(rider.id()), "ORDER_SELECTED"))
                .as("选定后飞手应收到 ORDER_SELECTED")
                .isPresent();
    }

    @Test
    @DisplayName("离线补偿：未连接 WS 时经 /chat/Message/sync 可拉到结构化系统消息")
    void offlineCompensationViaSyncEndpoint() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = createTask(owner);
        fixtures.selectAndLock(task, fixtures.rider().getId());
        fixtures.payLockedOrder(task);

        String response = get("/chat/Message/sync", owner.authorization());

        assertThat(response).contains("ORDER_PAID");
        assertThat(response).contains("\"type\":\"ORDER\"");
    }

    @Test
    @DisplayName("实时推送：接收方在线时状态变更经聊天 WS 推送结构化信封（msgType=2）")
    void realtimePushToConnectedRecipient() throws Exception {
        TestAccounts.Account owner = accounts().registerUser();
        Task task = createTask(owner);
        fixtures.selectAndLock(task, fixtures.rider().getId());

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        OwnerInboxEndpoint endpoint = new OwnerInboxEndpoint();
        Session session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(),
                URI.create(wsBaseUrl() + "/ws/" + owner.id() + "?token=" + owner.token()));
        try {
            assertThat(endpoint.opened.await(10, TimeUnit.SECONDS)).isTrue();
            fixtures.payLockedOrder(task);

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

    /** 造任务：任务名唯一（唯一命名由共享工厂保证）。 */
    private Task createTask(TestAccounts.Account owner) {
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        return taskService.createTask(fixtures.twoWaypointTask());
    }

    /** 造吊运任务（含货物字段），供生产应征链路使用。 */
    private Task createTransportTask(TestAccounts.Account owner) {
        UserContext.setUser(owner.id(), owner.userName(), owner.role());
        TaskDto dto = fixtures.twoWaypointTask(UniqueNames.unique("task"));
        dto.setType(TaskType.TRANSPORT);
        dto.setCargoWeightKg(new BigDecimal("2.00"));
        dto.setCargoCategory(CargoCategory.CONSTRUCTION);
        return taskService.createTask(dto);
    }

    private Optional<ChatEnvelope> findByName(List<ChatEnvelope> unread, String name) {
        return unread.stream()
                .filter(e -> e.getPayload() != null && name.equals(e.getPayload().get("name")))
                .findFirst();
    }

    /** 真实 TCP GET（RANDOM_PORT 下不再用 MockMvc）。 */
    private String get(String path, String authorization) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                                URI.create(baseUrl() + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", authorization)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("GET %s 期望 200，响应=%s", path, response.body()).isEqualTo(200);
        return response.body();
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
