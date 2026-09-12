package com.uav.live;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.uav.live.service.AppWebSocketService;
import com.uav.live.service.LiveSessionService;
import com.uav.server.util.JwtUtil;
import com.uav.uav.mapper.UavRepository;
import com.uav.uav.pojo.entity.Uav;
import com.uav.user.mapper.RiderUavRepository;
import com.uav.user.mapper.UserRecordRepository;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.RiderUav;
import com.uav.user.pojo.entity.User;
import com.uav.user.pojo.entity.UserRecord;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1A-5a 端到端协议测试：运营端 POST /live/close → 设备通道收到 STOP_LIVE →
 * 设备同步回执（response/replyTo）→ 直播会话终态 IDLE + 全体观看记录 end_time 补齐。
 * 同时覆盖：ACK 超时 → 异步 LIVE_STOPPED 事件兜底；设备离线补终态；直播未运行分支。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveStopProtocolTest {

    @LocalServerPort
    int port;

    @Autowired
    WebApplicationContext wac;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RiderUavRepository riderUavRepository;

    @Autowired
    UavRepository uavRepository;

    @Autowired
    UserRecordRepository userRecordRepository;

    @Autowired
    AppWebSocketService appWebSocketService;

    @Autowired
    LiveSessionService liveSessionService;

    MockMvc mockMvc;

    private long rid;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        rid = System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        // 无需清理：H2 数据按唯一 rid 隔离，WS 会话由设备端断开兜底
    }

    @Test
    @DisplayName("确认停止：设备收到 STOP_LIVE 并回执 → 会话转 IDLE、全体观看记录补 end_time")
    void confirmedStopFlow() throws Exception {
        String deviceId = "stop-a-" + rid;
        DeviceFixture fixture = connectDevice(deviceId, true);
        liveSessionService.markRunning(deviceId, "drone_" + deviceId, "req-" + rid);

        User caller = newCallerUser();
        User otherViewer = newViewerUser();
        UserRecord callerRecord = openRecord(caller.getUserName(), deviceId);
        UserRecord otherRecord = openRecord(otherViewer.getUserName(), deviceId);

        mockMvc.perform(post("/live/close")
                        .param("deviceId", deviceId)
                        .header("Authorization", bearer(caller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(fixture.device.stopCommandLatch.await(5, TimeUnit.SECONDS)).isTrue();
        String stopPayload = fixture.device.firstStopPayload();
        JSONObject command = JSON.parseObject(stopPayload);
        assertThat(command.getString("type")).isEqualTo("command");
        assertThat(command.getString("name")).isEqualTo("STOP_LIVE");
        assertThat(command.getString("deviceId")).isEqualTo(deviceId);
        assertThat(command.getString("id")).isNotBlank();

        assertThat(liveSessionService.isRunning(deviceId)).isFalse();
        assertThat(userRecordRepository.findById(callerRecord.getId()).orElseThrow().getEnd_time()).isNotNull();
        assertThat(userRecordRepository.findById(otherRecord.getId()).orElseThrow().getEnd_time()).isNotNull();

        fixture.close();
    }

    @Test
    @DisplayName("ACK 超时：发起方记录先关、其他观众记录等待确认，LIVE_STOPPED 事件兜底补齐")
    void ackTimeoutFallsBackToLiveStoppedEvent() throws Exception {
        String deviceId = "stop-b-" + rid;
        DeviceFixture fixture = connectDevice(deviceId, false); // 设备不回 ACK
        liveSessionService.markRunning(deviceId, "drone_" + deviceId, "req-" + rid);

        User caller = newCallerUser();
        User otherViewer = newViewerUser();
        UserRecord callerRecord = openRecord(caller.getUserName(), deviceId);
        UserRecord otherRecord = openRecord(otherViewer.getUserName(), deviceId);

        long start = System.currentTimeMillis();
        mockMvc.perform(post("/live/close")
                        .param("deviceId", deviceId)
                        .header("Authorization", bearer(caller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("停止命令已发送，等待设备确认"));
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isGreaterThanOrEqualTo(4000L); // ACK 超时窗口生效

        assertThat(fixture.device.stopCommandLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(liveSessionService.isRunning(deviceId)).isTrue(); // 超时未确认前不提前转 IDLE
        // 发起方记录已关（发起方停止观看，任何分支都生效）
        assertThat(userRecordRepository.findById(callerRecord.getId()).orElseThrow().getEnd_time()).isNotNull();
        // 其他观众记录在确认停止前保持打开
        assertThat(userRecordRepository.findById(otherRecord.getId()).orElseThrow().getEnd_time()).isNull();

        // 设备真正停止后异步上报 LIVE_STOPPED → LiveEventHandler 兜底：转 IDLE + 补齐全体观看记录
        fixture.device.send("{\"type\":\"event\",\"name\":\"LIVE_STOPPED\",\"deviceId\":\"" + deviceId
                + "\",\"timestamp\":" + System.currentTimeMillis() + "}");
        awaitFalse(() -> liveSessionService.isRunning(deviceId), 3, TimeUnit.SECONDS);
        assertThat(liveSessionService.isRunning(deviceId)).isFalse();
        assertThat(userRecordRepository.findById(otherRecord.getId()).orElseThrow().getEnd_time()).isNotNull();

        fixture.close();
    }

    @Test
    @DisplayName("设备离线：直接补终态并结束全体观看记录")
    void deviceOfflineClosesRecords() throws Exception {
        String deviceId = "stop-c-" + rid;
        registerUav(deviceId);

        User caller = newCallerUser();
        User otherViewer = newViewerUser();
        UserRecord callerRecord = openRecord(caller.getUserName(), deviceId);
        UserRecord otherRecord = openRecord(otherViewer.getUserName(), deviceId);

        mockMvc.perform(post("/live/close")
                        .param("deviceId", deviceId)
                        .header("Authorization", bearer(caller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("无人机已离线，直播已结束"));

        assertThat(liveSessionService.isRunning(deviceId)).isFalse();
        assertThat(userRecordRepository.findById(callerRecord.getId()).orElseThrow().getEnd_time()).isNotNull();
        assertThat(userRecordRepository.findById(otherRecord.getId()).orElseThrow().getEnd_time()).isNotNull();
    }

    @Test
    @DisplayName("直播未运行：仅补观看记录，不向设备下发 STOP_LIVE")
    void notRunningSkipsCommand() throws Exception {
        String deviceId = "stop-d-" + rid;
        DeviceFixture fixture = connectDevice(deviceId, true); // connectDevice 内部已注册 uav

        User caller = newCallerUser();
        UserRecord callerRecord = openRecord(caller.getUserName(), deviceId);

        mockMvc.perform(post("/live/close")
                        .param("deviceId", deviceId)
                        .header("Authorization", bearer(caller)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("直播未在运行，观看记录已结束"));

        Thread.sleep(500); // 留出潜在误下发的时间窗
        assertThat(fixture.device.stopCommandLatch.getCount()).isEqualTo(1); // 未收到任何 STOP_LIVE
        assertThat(userRecordRepository.findById(callerRecord.getId()).orElseThrow().getEnd_time()).isNotNull();

        fixture.close();
    }

    // ---------- 设备端模拟 ----------

    private record DeviceFixture(DeviceEndpoint device, Session session) {
        void close() {
            try {
                session.close();
            } catch (IOException ignored) {
            }
        }
    }

    private DeviceFixture connectDevice(String deviceId, boolean autoAck) throws Exception {
        User rider = newUser("rider" + rid + "-" + deviceId.hashCode(), 1);
        RiderUav binding = new RiderUav();
        binding.setUserId(rider.getId());
        binding.setDjiId(deviceId);
        riderUavRepository.save(binding);
        registerUav(deviceId);

        appWebSocketService.requestConnection(deviceId); // pending 门

        String token = jwtUtil.generateToken(rider.getId(), rider.getUserName(), 1);
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        DeviceEndpoint endpoint = new DeviceEndpoint(autoAck);
        Session session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(),
                URI.create("ws://localhost:" + port + "/ws/drone?deviceId=" + deviceId + "&token=" + token));
        assertThat(endpoint.opened.await(10, TimeUnit.SECONDS)).isTrue();

        // afterConnectionEstablished 异步 markAsConnected，轮询等待注册完成
        long deadline = System.currentTimeMillis() + 5000;
        while (!appWebSocketService.isConnected(deviceId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(appWebSocketService.isConnected(deviceId)).isTrue();
        return new DeviceFixture(endpoint, session);
    }

    private void registerUav(String deviceId) {
        Uav uav = new Uav();
        uav.setUavName("ln-" + deviceId);
        uav.setDjiId(deviceId);
        uav.setOnlineStatus('1');
        uav.setControllerModel("cm");
        uav.setIsAvailable('1');
        uavRepository.save(uav);
    }

    /** 模拟设备端：收集下行消息，可选对 STOP_LIVE 自动回同步 ACK（response/replyTo）。 */
    static class DeviceEndpoint extends Endpoint {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch stopCommandLatch = new CountDownLatch(1);
        final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();
        final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
        private final boolean autoAck;
        private volatile String firstStopPayload;

        DeviceEndpoint(boolean autoAck) {
            this.autoAck = autoAck;
        }

        String firstStopPayload() {
            return firstStopPayload;
        }

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            sessions.add(session);
            // 注意：Tomcat 通过反射读取泛型参数，必须用匿名类（lambda 会抛 IllegalStateException）
            session.addMessageHandler(new jakarta.websocket.MessageHandler.Whole<String>() {
                @Override
                public void onMessage(String message) {
                    onDeviceMessage(session, message);
                }
            });
            opened.countDown();
        }

        private void onDeviceMessage(Session session, String message) {
            messages.add(message);
            if (message.contains("STOP_LIVE")) {
                if (firstStopPayload == null) {
                    firstStopPayload = message;
                }
                stopCommandLatch.countDown();
                if (autoAck) {
                    JSONObject command = JSON.parseObject(message);
                    JSONObject ack = new JSONObject();
                    ack.put("type", "response");
                    ack.put("replyTo", command.getString("id"));
                    ack.put("success", true);
                    try {
                        session.getBasicRemote().sendText(ack.toJSONString());
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        void send(String text) {
            for (Session session : sessions) {
                try {
                    session.getBasicRemote().sendText(text);
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ---------- 公共助手 ----------

    private User newCallerUser() {
        return newUser("caller" + rid, 0);
    }

    private User newViewerUser() {
        return newUser("viewer" + rid, 0);
    }

    private User newUser(String name, int role) {
        User user = new User();
        user.setUserName(name + "-" + UUID.randomUUID().toString().substring(0, 6));
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
    }

    private UserRecord openRecord(String userName, String deviceId) {
        UserRecord record = new UserRecord();
        record.setUserName(userName);
        record.setDjiId(deviceId);
        record.setStart_time(LocalDateTime.now());
        return userRecordRepository.save(record);
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.generateToken(user.getId(), user.getUserName(), user.getRole());
    }

    private void awaitFalse(java.util.function.BooleanSupplier retryCondition, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (retryCondition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }
}
