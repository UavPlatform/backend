package com.uav.chat;

import com.uav.live.service.AppWebSocketService;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.TestAccounts;
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

import java.net.URI;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端协议测试（t35 / 裁决 Q12=A 强制单设备在线）：
 * 聊天 {@code /ws/{sid}} 被「更新登录」顶掉的旧连接以 4001（replaced by newer login）关闭；
 * 客户端主动登出仍为 1000 语义且可立即重连；4001 语义不外溢到 {@code /ws/drone} 设备通道。
 *
 * <p>真实驱动方式（R9/O4）：Jakarta WebSocket 客户端握手真实 TCP 端口，因此继承
 * {@link RealProtocolTestBase}（{@code RANDOM_PORT}，不含 {@code @Transactional}）。
 *
 * <p>R5 隔离方式：WebSocket 连接跨事务边界，无法用事务回滚。本类改用真实注册产生
 * <b>唯一 userId</b>（{@code TestAccounts} 每次调用分配唯一用户名与 DJI ID），
 * 会话状态按 userId 隔离，测试间不共享键；<b>不再以 {@code System.nanoTime()} 兜底</b>。
 * 每个用例结束时关闭自己建立的 WS 会话（显式清理）。
 *
 * <p>R3：握手 token 由 {@link TestAccounts} 真实注册/登录取得，不再 {@code JwtUtil.generateToken} 自签。
 */
class ChatKickCloseCodeE2EIT extends RealProtocolTestBase {

    @Autowired
    private AppWebSocketService appWebSocketService;

    private final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeSessions() {
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
        TestAccounts.Account account = accounts().registerUser();
        URI uri = URI.create(wsBaseUrl() + "/ws/" + account.id() + "?token=" + account.token());

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
        TestAccounts.Account account = accounts().registerUser();
        URI uri = URI.create(wsBaseUrl() + "/ws/" + account.id() + "?token=" + account.token());

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
        // 真实注册飞手：djiId 即绑定设备号，token 由服务端签发
        TestAccounts.Account rider = accounts().registerRider();
        String deviceId = rider.djiId();

        TrackingEndpoint first = connectDevice(rider, deviceId);
        TrackingEndpoint second = connectDevice(rider, deviceId);

        assertThat(first.opened.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(second.opened.await(10, TimeUnit.SECONDS)).isTrue();

        // 观察窗口：旧会话未被 4001 关闭（设备通道无顶号语义，FLY 侧另议）
        assertThat(first.closed.await(1500, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(first.closeReason).isNull();
    }

    // ---------- helpers ----------

    private TrackingEndpoint connectDevice(TestAccounts.Account rider, String deviceId) throws Exception {
        appWebSocketService.requestConnection(deviceId); // pending 门

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        TrackingEndpoint endpoint = new TrackingEndpoint();
        Session session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(),
                URI.create(wsBaseUrl() + "/ws/drone?deviceId=" + deviceId
                        + "&token=" + rider.token()));
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
