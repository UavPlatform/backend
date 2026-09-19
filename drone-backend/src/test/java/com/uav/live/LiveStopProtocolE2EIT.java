package com.uav.live;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jayway.jsonpath.JsonPath;
import com.uav.live.service.AppWebSocketService;
import com.uav.live.service.LiveSessionService;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.uav.mapper.UavRepository;
import com.uav.uav.pojo.entity.Uav;
import com.uav.user.mapper.UserRecordRepository;
import com.uav.user.pojo.entity.UserRecord;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端协议测试（1A-5a）：运营端 {@code POST /live/close} → 设备通道收到 STOP_LIVE →
 * 设备同步回执（response/replyTo）→ 直播会话终态 IDLE + 全体观看记录 end_time 补齐。
 * 同时覆盖：ACK 超时 → 异步 LIVE_STOPPED 事件兜底；设备离线补终态；直播未运行分支。
 *
 * <p>真实驱动方式（R9/O4）：设备通道 {@code /ws/drone} 与 ACK 时序均为真实 WebSocket，
 * 因此继承 {@link RealProtocolTestBase}（{@code RANDOM_PORT}，不含 {@code @Transactional}）；
 * REST 调用同步升级为真实 TCP（JDK HttpClient，见 {@link #post}），不再用 MockMvc 混合两套请求栈。
 *
 * <p>R5 隔离方式：无事务可回滚，唯一性由 {@link TestAccounts}（唯一身份）与
 * {@code UniqueNames}（唯一 deviceId）保证，<b>不再以 {@code System.nanoTime()} 兜底</b>；
 * 每个用例结束关闭自己建立的 WS 会话（显式清理）。
 *
 * <p>R3：运营端与观看者 token 均由 {@link TestAccounts} 真实注册取得，设备握手用真实飞手 token。
 */
class LiveStopProtocolE2EIT extends RealProtocolTestBase {

    @Autowired
    private UavRepository uavRepository;

    @Autowired
    private UserRecordRepository userRecordRepository;

    @Autowired
    private AppWebSocketService appWebSocketService;

    @Autowired
    private LiveSessionService liveSessionService;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    @DisplayName("确认停止：设备收到 STOP_LIVE 并回执 → 会话转 IDLE、全体观看记录补 end_time")
    void confirmedStopFlow() throws Exception {
        String deviceId = UniqueNames.unique("stop-a");
        DeviceFixture fixture = connectDevice(deviceId, true);
        liveSessionService.markRunning(deviceId, "drone_" + deviceId, UniqueNames.unique("req"));

        TestAccounts.Account caller = newCallerAccount();
        TestAccounts.Account otherViewer = newViewerAccount();
        UserRecord callerRecord = openRecord(caller.userName(), deviceId);
        UserRecord otherRecord = openRecord(otherViewer.userName(), deviceId);

        String closeBody = post("/live/close?deviceId=" + deviceId, caller.authorization());
        assertThat((Boolean) JsonPath.read(closeBody, "$.success")).isTrue();

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
        String deviceId = UniqueNames.unique("stop-b");
        DeviceFixture fixture = connectDevice(deviceId, false); // 设备不回 ACK
        liveSessionService.markRunning(deviceId, "drone_" + deviceId, UniqueNames.unique("req"));

        TestAccounts.Account caller = newCallerAccount();
        TestAccounts.Account otherViewer = newViewerAccount();
        UserRecord callerRecord = openRecord(caller.userName(), deviceId);
        UserRecord otherRecord = openRecord(otherViewer.userName(), deviceId);

        long start = System.currentTimeMillis();
        String closeBody = post("/live/close?deviceId=" + deviceId, caller.authorization());
        assertThat((String) JsonPath.read(closeBody, "$.message")).isEqualTo("停止命令已发送，等待设备确认");
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
        String deviceId = UniqueNames.unique("stop-c");
        registerUav(deviceId);

        TestAccounts.Account caller = newCallerAccount();
        TestAccounts.Account otherViewer = newViewerAccount();
        UserRecord callerRecord = openRecord(caller.userName(), deviceId);
        UserRecord otherRecord = openRecord(otherViewer.userName(), deviceId);

        String closeBody = post("/live/close?deviceId=" + deviceId, caller.authorization());
        assertThat((String) JsonPath.read(closeBody, "$.message")).isEqualTo("无人机已离线，直播已结束");

        assertThat(liveSessionService.isRunning(deviceId)).isFalse();
        assertThat(userRecordRepository.findById(callerRecord.getId()).orElseThrow().getEnd_time()).isNotNull();
        assertThat(userRecordRepository.findById(otherRecord.getId()).orElseThrow().getEnd_time()).isNotNull();
    }

    @Test
    @DisplayName("直播未运行：仅补观看记录，不向设备下发 STOP_LIVE")
    void notRunningSkipsCommand() throws Exception {
        String deviceId = UniqueNames.unique("stop-d");
        DeviceFixture fixture = connectDevice(deviceId, true); // connectDevice 内部已注册 uav

        TestAccounts.Account caller = newCallerAccount();
        UserRecord callerRecord = openRecord(caller.userName(), deviceId);

        String closeBody = post("/live/close?deviceId=" + deviceId, caller.authorization());
        assertThat((String) JsonPath.read(closeBody, "$.message")).isEqualTo("直播未在运行，观看记录已结束");

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
        TestAccounts.Account rider = accounts().registerRider();
        // /ws/drone 握手要求该 deviceId 已绑定到当前飞手：把本例设备真实绑定到同一 riderId
        fixtures.bindDrone(rider.id(), deviceId);
        registerUav(deviceId);

        appWebSocketService.requestConnection(deviceId); // pending 门

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        DeviceEndpoint endpoint = new DeviceEndpoint(autoAck);
        Session session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(),
                URI.create(wsBaseUrl() + "/ws/drone?deviceId=" + deviceId
                        + "&token=" + rider.token()));
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

    private TestAccounts.Account newCallerAccount() {
        return accounts().registerUser();
    }

    private TestAccounts.Account newViewerAccount() {
        return accounts().registerUser();
    }

    private UserRecord openRecord(String userName, String deviceId) {
        UserRecord record = new UserRecord();
        record.setUserName(userName);
        record.setDjiId(deviceId);
        record.setStart_time(LocalDateTime.now());
        return userRecordRepository.save(record);
    }

    /** 真实 TCP POST（RANDOM_PORT 下不再用 MockMvc），返回响应体供 JSON 断言。 */
    private String post(String urlAndParams, String bearer) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                                URI.create(baseUrl() + urlAndParams))
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", bearer)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("POST %s 期望 200，响应=%s", urlAndParams, response.body())
                .isEqualTo(200);
        return response.body();
    }

    private void awaitFalse(java.util.function.BooleanSupplier retryCondition, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (retryCondition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }
}
