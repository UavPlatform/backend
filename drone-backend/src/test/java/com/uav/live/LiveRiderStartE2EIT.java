package com.uav.live;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.uav.live.service.AppWebSocketService;
import com.uav.live.service.LiveSessionService;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.uav.mapper.UavRepository;
import com.uav.uav.pojo.entity.Uav;
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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端协议测试（1B-4b / 裁决 Q3=C）：飞手主动开播。
 * 覆盖：绑定+在线设备 START_LIVE 下发与确认、未绑定 403、离线 409、
 * 运营端 {@code /live/req} 回归、t12 STOP_LIVE 停止链路对飞手开播同样生效。
 *
 * <p>真实驱动方式（R9/O4）：设备通道 {@code /ws/drone} 为真实 WebSocket 握手，因此继承
 * {@link RealProtocolTestBase}（{@code RANDOM_PORT}，不含 {@code @Transactional}）；
 * REST 调用同步升级为真实 TCP（JDK HttpClient，见 {@link #post}），与真实协议层一致，
 * 不再用 MockMvc 混合两套请求栈。
 *
 * <p>R5 隔离方式：无事务可回滚，唯一性由 {@link TestAccounts}（唯一用户名/DJI ID）与
 * {@code UniqueNames}（唯一 deviceId）保证，<b>不再以 {@code System.nanoTime()} 兜底</b>；
 * 每个用例结束关闭自己建立的 WS 会话（显式清理）。
 *
 * <p>R3：飞手与运营端 token 均由 {@link TestAccounts} 真实注册取得，握手鉴权同样用真实 token。
 */
class LiveRiderStartE2EIT extends RealProtocolTestBase {

    @Autowired
    private UavRepository uavRepository;

    @Autowired
    private AppWebSocketService appWebSocketService;

    @Autowired
    private LiveSessionService liveSessionService;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    @DisplayName("飞手对本人绑定且在线设备开播：START_LIVE 下发、设备确认后 RUNNING，停止链路生效")
    void riderStartLiveFullFlow() throws Exception {
        DeviceFixture fixture = connectDevice(true);
        String deviceId = fixture.deviceId;

        // 飞手主动开播
        var startResponse = post("/live/rider/req?deviceId=" + deviceId, fixture.rider.authorization());
        assertThat(startResponse).contains("设备已确认启动图传");
        assertThat(fixture.device.startLiveLatch.await(5, TimeUnit.SECONDS)).isTrue();
        JSONObject startCmd = JSON.parseObject(fixture.device.firstStartLivePayload());
        assertThat(startCmd.getString("type")).isEqualTo("command");
        assertThat(startCmd.getString("name")).isEqualTo("START_LIVE");
        assertThat(startCmd.getString("deviceId")).isEqualTo(deviceId);
        assertThat(startCmd.getJSONObject("data").getString("roomId")).isEqualTo("drone_" + deviceId);
        assertThat(startCmd.getJSONObject("data").getString("userSig")).isNotBlank();

        // 设备 ack 成功 → 会话 RUNNING
        assertThat(liveSessionService.isRunning(deviceId)).isTrue();

        // t12 停止链路对飞手开播同样生效（任意已登录方关闭 → STOP_LIVE → IDLE）
        var closeResponse = post("/live/close?deviceId=" + deviceId, operatorToken());
        assertThat(closeResponse).contains("设备已确认停止推流");
        assertThat(fixture.device.stopLiveLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(liveSessionService.isRunning(deviceId)).isFalse();

        fixture.close();
    }

    @Test
    @DisplayName("未绑定设备：飞手开播被拒（403 NO_PERMISSION，语义明确）")
    void riderStartUnboundDeviceRejected() throws Exception {
        String deviceId = UniqueNames.unique("rs-unbound");
        registerUav(deviceId);
        connectDeviceless(deviceId); // 设备在线，但未绑定到任何飞手
        // 真实注册飞手时绑定的设备与本用例的 deviceId 不同 → 未绑定
        TestAccounts.Account rider = accounts().registerRider();
        assertThat(rider.djiId()).isNotEqualTo(deviceId);

        String body = post("/live/rider/req?deviceId=" + deviceId, rider.authorization());
        assertThat(body).contains("设备未绑定到当前飞手");
        assertThat(body).contains("NO_PERMISSION");
    }

    @Test
    @DisplayName("离线设备：飞手开播被拒（409 UAV_NOT_CONNECTED）")
    void riderStartOfflineDeviceRejected() throws Exception {
        // 飞手真实注册时已绑定唯一设备：该设备已注册但从未建立 WS 连接 → 离线
        TestAccounts.Account rider = accounts().registerRider();
        String deviceId = rider.djiId();
        registerUav(deviceId);

        String body = post("/live/rider/req?deviceId=" + deviceId, rider.authorization());
        assertThat(body).contains("UAV_NOT_CONNECTED");
    }

    @Test
    @DisplayName("回归：运营端 /live/req 既有通道不受影响")
    void operatorStartLiveRegression() throws Exception {
        DeviceFixture fixture = connectDevice(true);
        String deviceId = fixture.deviceId;

        String body = post("/live/req?deviceId=" + deviceId, operatorToken());
        assertThat(body).contains("设备已确认启动图传");
        assertThat(fixture.device.startLiveLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(liveSessionService.isRunning(deviceId)).isTrue();

        fixture.close();
    }

    // ---------- helpers ----------

    /** 真实注册的运营/普通用户身份（原本地造 role=0 用户 + 自签 token）。 */
    private String operatorToken() {
        return accounts().registerUser().authorization();
    }

    private void registerUav(String deviceId) {
        Uav uav = new Uav();
        uav.setUavName("rs-" + deviceId);
        uav.setDjiId(deviceId);
        uav.setOnlineStatus('1');
        uav.setControllerModel("cm");
        uav.setIsAvailable('1');
        uavRepository.save(uav);
    }

    /** 真实 TCP POST（RANDOM_PORT 下不再用 MockMvc），返回响应体供断言。 */
    private String post(String urlAndParams, String bearer) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                                URI.create(baseUrl() + urlAndParams))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", bearer)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response.body();
    }

    /** 绑定+pending+WS 连接一台设备（自动回 START_LIVE/STOP_LIVE 的同步 ACK）。 */
    private DeviceFixture connectDevice(boolean autoAck) throws Exception {
        TestAccounts.Account rider = accounts().registerRider();
        String deviceId = rider.djiId();
        registerUav(deviceId);
        appWebSocketService.requestConnection(deviceId);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        DeviceEndpoint endpoint = new DeviceEndpoint(autoAck);
        Session session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(),
                URI.create(wsBaseUrl() + "/ws/drone?deviceId=" + deviceId
                        + "&token=" + rider.token()));
        assertThat(endpoint.opened.await(10, TimeUnit.SECONDS)).isTrue();
        long deadline = System.currentTimeMillis() + 5000;
        while (!appWebSocketService.isConnected(deviceId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(appWebSocketService.isConnected(deviceId)).isTrue();
        return new DeviceFixture(deviceId, endpoint, session, rider);
    }

    /** 设备在线（仅 pending 门），无 WS 会话。 */
    private void connectDeviceless(String deviceId) {
        appWebSocketService.requestConnection(deviceId);
        appWebSocketService.markAsConnected(deviceId);
    }

    private record DeviceFixture(String deviceId, DeviceEndpoint device, Session session, TestAccounts.Account rider) {
        void close() {
            try {
                session.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 模拟设备端：记录 START_LIVE/STOP_LIVE 下行，可选自动回同步 ACK。 */
    static class DeviceEndpoint extends Endpoint {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch startLiveLatch = new CountDownLatch(1);
        final CountDownLatch stopLiveLatch = new CountDownLatch(1);
        final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();
        final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
        private final boolean autoAck;
        private volatile String firstStartLivePayload;

        DeviceEndpoint(boolean autoAck) {
            this.autoAck = autoAck;
        }

        String firstStartLivePayload() {
            return firstStartLivePayload;
        }

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            sessions.add(session);
            session.addMessageHandler(new MessageHandler.Whole<String>() {
                @Override
                public void onMessage(String message) {
                    messages.add(message);
                    if (message.contains("START_LIVE")) {
                        if (firstStartLivePayload == null) {
                            firstStartLivePayload = message;
                        }
                        startLiveLatch.countDown();
                        if (autoAck) {
                            ack(session, message);
                        }
                    } else if (message.contains("STOP_LIVE")) {
                        stopLiveLatch.countDown();
                        if (autoAck) {
                            ack(session, message);
                        }
                    }
                }
            });
            opened.countDown();
        }

        private void ack(Session session, String commandPayload) {
            JSONObject command = JSON.parseObject(commandPayload);
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
