package com.uav.live;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.uav.live.service.AppWebSocketService;
import com.uav.live.service.LiveSessionService;
import com.uav.server.util.JwtUtil;
import com.uav.uav.mapper.UavRepository;
import com.uav.uav.pojo.entity.Uav;
import com.uav.user.mapper.RiderUavRepository;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.RiderUav;
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

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1B-4b 端到端测试（裁决 Q3=C）：飞手主动开播。
 * 覆盖：绑定+在线设备 START_LIVE 下发与确认、未绑定 403、离线 409、
 * 运营端 /live/req 回归、t12 STOP_LIVE 停止链路对飞手开播同样生效。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveRiderStartTest {

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RiderUavRepository riderUavRepository;

    @Autowired
    UavRepository uavRepository;

    @Autowired
    AppWebSocketService appWebSocketService;

    @Autowired
    LiveSessionService liveSessionService;

    @Autowired
    org.springframework.web.context.WebApplicationContext wac;

    private long rid;

    @BeforeEach
    void setUp() {
        rid = System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        // 设备会话由各用例自行关闭
    }

    @Test
    @DisplayName("飞手对本人绑定且在线设备开播：START_LIVE 下发、设备确认后 RUNNING，停止链路生效")
    void riderStartLiveFullFlow() throws Exception {
        DeviceFixture fixture = connectDevice(true);
        String deviceId = fixture.deviceId;

        // 飞手主动开播
        var startResponse = post("/live/rider/req?deviceId=" + deviceId, riderToken(fixture.rider));
        assertThat(startResponse).contains("设备已确认启动图传");
        assertThat(fixture.device.startLiveLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
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
        assertThat(fixture.device.stopLiveLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(liveSessionService.isRunning(deviceId)).isFalse();

        fixture.close();
    }

    @Test
    @DisplayName("未绑定设备：飞手开播被拒（403 NO_PERMISSION，语义明确）")
    void riderStartUnboundDeviceRejected() throws Exception {
        String deviceId = "rs-unbound-" + rid;
        registerUav(deviceId);
        connectDeviceless(deviceId); // 设备在线，但未绑定到任何飞手
        User rider = newUser(1);
        bindRider(rider.getId(), "other-drone-" + rid); // 绑定的是另一台设备

        String body = post("/live/rider/req?deviceId=" + deviceId, riderToken(rider));
        assertThat(body).contains("设备未绑定到当前飞手");
        assertThat(body).contains("NO_PERMISSION");
    }

    @Test
    @DisplayName("离线设备：飞手开播被拒（409 UAV_NOT_CONNECTED）")
    void riderStartOfflineDeviceRejected() throws Exception {
        String deviceId = "rs-offline-" + rid;
        registerUav(deviceId);
        User rider = newUser(1);
        bindRider(rider.getId(), deviceId);

        String body = post("/live/rider/req?deviceId=" + deviceId, riderToken(rider));
        assertThat(body).contains("UAV_NOT_CONNECTED");
    }

    @Test
    @DisplayName("回归：运营端 /live/req 既有通道不受影响")
    void operatorStartLiveRegression() throws Exception {
        DeviceFixture fixture = connectDevice(true);
        String deviceId = fixture.deviceId;

        String body = post("/live/req?deviceId=" + deviceId, operatorToken());
        assertThat(body).contains("设备已确认启动图传");
        assertThat(fixture.device.startLiveLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(liveSessionService.isRunning(deviceId)).isTrue();

        fixture.close();
    }

    // ---------- helpers ----------

    private User newUser(int role) {
        User user = new User();
        user.setUserName("rsl" + rid + "-" + role + "-" + UUID.randomUUID().toString().substring(0, 6));
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
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

    private void bindRider(Long riderId, String deviceId) {
        RiderUav binding = new RiderUav();
        binding.setUserId(riderId);
        binding.setDjiId(deviceId);
        riderUavRepository.save(binding);
    }

    private String riderToken(User rider) {
        return "Bearer " + jwtUtil.generateToken(rider.getId(), rider.getUserName(), 1);
    }

    private String operatorToken() {
        User operator = newUser(0);
        return "Bearer " + jwtUtil.generateToken(operator.getId(), operator.getUserName(), 0);
    }

    private String post(String urlAndParams, String bearer) throws Exception {
        var mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(wac).build();
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(urlAndParams).header("Authorization", bearer))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    /** 绑定+pending+WS 连接一台设备（自动回 START_LIVE/STOP_LIVE 的同步 ACK）。 */
    private DeviceFixture connectDevice(boolean autoAck) throws Exception {
        String deviceId = "rs-dev-" + rid + "-" + UUID.randomUUID().toString().substring(0, 6);
        User rider = newUser(1);
        bindRider(rider.getId(), deviceId);
        registerUav(deviceId);
        appWebSocketService.requestConnection(deviceId);

        String token = jwtUtil.generateToken(rider.getId(), rider.getUserName(), 1);
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        DeviceEndpoint endpoint = new DeviceEndpoint(autoAck);
        Session session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(),
                URI.create("ws://localhost:" + port + "/ws/drone?deviceId=" + deviceId + "&token=" + token));
        assertThat(endpoint.opened.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
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

    private record DeviceFixture(String deviceId, DeviceEndpoint device, Session session, User rider) {
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
