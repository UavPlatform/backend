package com.uav.chat;

import com.uav.live.service.AppWebSocketService;
import com.uav.server.util.JwtUtil;
import com.uav.user.mapper.RiderUavRepository;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.RiderUav;
import com.uav.user.pojo.entity.User;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * t35 契约测试（裁决 Q12=A 强制单设备在线）：
 * 聊天 /ws/{sid} 被「更新登录」顶掉的旧连接以 4001（replaced by newer login）关闭；
 * 客户端主动登出仍为 1000 语义且可立即重连；4001 语义不外溢到 /ws/drone 设备通道。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ChatKickCloseCodeTest {

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

    private final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        for (Session s : sessions) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
    }

    @Test
    @DisplayName("顶号：旧连接收到 close 4001（replaced by newer login），新连接保持在线")
    void replacedConnectionGets4001() throws Exception {
        long userId = 3_000_000 + (Math.abs(UUID.randomUUID().getLeastSignificantBits()) % 1_000_000);
        String token = jwtUtil.generateToken(userId, "kick" + userId, 0);
        URI uri = URI.create("ws://localhost:" + port + "/ws/" + userId + "?token=" + token);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        TrackingEndpoint first = new TrackingEndpoint();
        Session firstSession = container.connectToServer(first, ClientEndpointConfig.Builder.create().build(), uri);
        sessions.add(firstSession);
        assertThat(first.opened.await(10, TimeUnit.SECONDS)).isTrue();

        TrackingEndpoint second = new TrackingEndpoint();
        Session secondSession = container.connectToServer(second, ClientEndpointConfig.Builder.create().build(), uri);
        sessions.add(secondSession);
        assertThat(second.opened.await(10, TimeUnit.SECONDS)).isTrue();

        // 旧连接（first）被顶：服务端下发 4001
        assertThat(first.closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(first.closeReason.getCloseCode().getCode()).isEqualTo(4001);
        assertThat(first.closeReason.getReasonPhrase()).isEqualTo("replaced by newer login");

        // 新连接不受影响
        assertThat(secondSession.isOpen()).isTrue();
        assertThat(second.closed.await(300, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    @DisplayName("正常登出：客户端主动关闭仍为 1000 语义，且可立即重连")
    void normalLogoutStillWorks() throws Exception {
        long userId = 5_000_000 + (Math.abs(UUID.randomUUID().getLeastSignificantBits()) % 1_000_000);
        String token = jwtUtil.generateToken(userId, "logout" + userId, 0);
        URI uri = URI.create("ws://localhost:" + port + "/ws/" + userId + "?token=" + token);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        TrackingEndpoint endpoint = new TrackingEndpoint();
        Session session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(), uri);
        sessions.add(session);
        assertThat(endpoint.opened.await(10, TimeUnit.SECONDS)).isTrue();

        // 客户端主动登出：自己发起 1000 关闭
        session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "logout"));
        assertThat(endpoint.closed.await(5, TimeUnit.SECONDS)).isTrue();

        // 立即重连成功（服务端不拒绝正常登出后的重连）
        TrackingEndpoint again = new TrackingEndpoint();
        Session reconnected = container.connectToServer(again, ClientEndpointConfig.Builder.create().build(), uri);
        sessions.add(reconnected);
        assertThat(again.opened.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(reconnected.isOpen()).isTrue();
    }

    @Test
    @DisplayName("设备通道不受 4001 语义影响：同 deviceId 二次连接不关闭旧会话")
    void droneChannelUnaffectedByKickSemantics() throws Exception {
        String deviceId = "kick-dev-" + UUID.randomUUID().toString().substring(0, 8);
        User rider = newUser(1);

        TrackingEndpoint first = connectDevice(rider, deviceId);
        TrackingEndpoint second = connectDevice(rider, deviceId);

        assertThat(first.opened.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(second.opened.await(10, TimeUnit.SECONDS)).isTrue();

        // 观察窗口：旧会话未被 4001 关闭（设备通道无顶号语义，FLY 侧另议）
        assertThat(first.closed.await(1500, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(first.closeReason).isNull();
    }

    // ---------- helpers ----------

    private User newUser(int role) {
        User user = new User();
        user.setUserName("kick" + UUID.randomUUID().toString().substring(0, 8));
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
    }

    private TrackingEndpoint connectDevice(com.uav.user.pojo.entity.User rider, String deviceId) throws Exception {
        RiderUav binding = new RiderUav();
        binding.setUserId(rider.getId());
        binding.setDjiId(deviceId);
        riderUavRepository.save(binding);

        appWebSocketService.requestConnection(deviceId); // pending 门

        String token = jwtUtil.generateToken(rider.getId(), rider.getUserName(), 1);
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        TrackingEndpoint endpoint = new TrackingEndpoint();
        Session session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(),
                URI.create("ws://localhost:" + port + "/ws/drone?deviceId=" + deviceId + "&token=" + token));
        sessions.add(session);
        return endpoint;
    }

    /** 记录关闭原因的测试端点。 */
    static class TrackingEndpoint extends Endpoint {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile CloseReason closeReason;

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            opened.countDown();
        }

        @Override
        public void onClose(Session session, CloseReason closeReason) {
            this.closeReason = closeReason;
            closed.countDown();
        }
    }
}
