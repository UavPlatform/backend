package com.uav.security;

import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.repository.ChatMessageRepository;
import com.uav.chat.repository.ChatSessionRepository;
import com.uav.chat.repository.ChatUserSessionRepository;
import com.uav.live.service.AppWebSocketService;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.uav.uav.mapper.UavRepository;
import com.uav.uav.pojo.entity.Uav;
import com.uav.user.mapper.RiderUavRepository;
import com.uav.user.mapper.UserRecordRepository;
import com.uav.user.pojo.entity.RiderUav;
import com.uav.user.pojo.entity.UserRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层越权防护测试（P0-2/4/5/6/7/9）：跨用户/跨角色的访问必须被拒。
 *
 * <p>层次与驱动（O6/R2/R4）：进程内 MockMvc 集成测试，继承 {@link IntegrationTestBase}
 * （MOCK + {@code @AutoConfigureMockMvc} + {@code @Transactional}），不自称端到端。
 *
 * <p>身份来源（R3）：需要合法 token 的场景一律走 {@link TestAccounts} 的真实注册/登录接口，
 * 不再用 {@code JwtUtil.generateToken} 自签绕过认证链路；造数走共享基建（R7），
 * ThreadLocal 由基类统一清理（R8），事务回滚即隔离（R5），故不再保留手工唯一名与手工删除。
 *
 * <p>例外：本类需要一个 {@code Uav} 实体行，而 R7 的共享工厂只覆盖用户/飞手/任务，故此处保留
 * 局部造数（属 R7 明文范围之外，不构成重复造数违规）。
 */
class HttpAccessControlIT extends IntegrationTestBase {

    @Autowired
    RiderUavRepository riderUavRepository;

    @Autowired
    UavRepository uavRepository;

    @Autowired
    UserRecordRepository userRecordRepository;

    @Autowired
    ChatSessionRepository chatSessionRepository;

    @Autowired
    ChatUserSessionRepository chatUserSessionRepository;

    @Autowired
    ChatMessageRepository chatMessageRepository;

    @Autowired
    AppWebSocketService appWebSocketService;

    // ---------- ② 管理端点角色门 ----------

    @Test
    @DisplayName("/admin/uav 普通用户访问 403")
    void adminUavForbiddenForNormalUser() throws Exception {
        TestAccounts.Account user = accounts().registerUser();
        mockMvc.perform(get("/admin/uav").header("Authorization", user.authorization()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/admin/uav 飞手访问 403")
    void adminUavForbiddenForRider() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("rid"), null);
        mockMvc.perform(get("/admin/uav/statistics").header("Authorization", rider.authorization()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/admin/uav 管理员访问 200")
    void adminUavOkForAdmin() throws Exception {
        TestAccounts.AdminAccount admin = accounts().adminLogin();
        mockMvc.perform(get("/admin/uav").header("Authorization", admin.authorization()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("/admin/logs 普通用户访问 403（日志泄露防护）")
    void adminLogsForbiddenForNormalUser() throws Exception {
        TestAccounts.Account user = accounts().registerUser();
        mockMvc.perform(get("/admin/logs/files").header("Authorization", user.authorization()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/logs/application").header("Authorization", user.authorization()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/logs/error").header("Authorization", user.authorization()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/admin/logs/files 管理员访问 200")
    void adminLogsOkForAdmin() throws Exception {
        TestAccounts.AdminAccount admin = accounts().adminLogin();
        mockMvc.perform(get("/admin/logs/files").header("Authorization", admin.authorization()))
                .andExpect(status().isOk());
    }

    // ---------- ⑤ 观看记录限制本人 ----------

    @Test
    @DisplayName("/webUav/getRecord 普通用户传他人 userName 时强制查自己")
    void getRecordForcedToSelf() throws Exception {
        TestAccounts.Account other = accounts().registerUser();
        UserRecord otherRecord = new UserRecord();
        otherRecord.setUserName(other.userName());
        otherRecord.setDjiId(UniqueNames.djiId());
        otherRecord.setStart_time(java.time.LocalDateTime.now());
        userRecordRepository.save(otherRecord);

        TestAccounts.Account self = accounts().registerUser();
        // self 显式传了 other 的 userName，但服务端必须按登录态查询 → 结果为空
        mockMvc.perform(get("/webUav/getRecord")
                        .param("userName", other.userName())
                        .header("Authorization", self.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(0));

        // 自查本人记录 → 能看到自己的一条
        UserRecord selfRecord = new UserRecord();
        selfRecord.setUserName(self.userName());
        selfRecord.setDjiId(UniqueNames.djiId());
        selfRecord.setStart_time(java.time.LocalDateTime.now());
        userRecordRepository.save(selfRecord);

        mockMvc.perform(get("/webUav/getRecord")
                        .param("userName", other.userName())
                        .header("Authorization", self.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("/webUav/getRecord 管理员可代查他人记录")
    void getRecordAdminOverride() throws Exception {
        TestAccounts.Account other = accounts().registerUser();
        UserRecord record = new UserRecord();
        record.setUserName(other.userName());
        record.setDjiId(UniqueNames.djiId());
        record.setStart_time(java.time.LocalDateTime.now());
        userRecordRepository.save(record);

        TestAccounts.AdminAccount admin = accounts().adminLogin();
        mockMvc.perform(get("/webUav/getRecord")
                        .param("userName", other.userName())
                        .header("Authorization", admin.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    // ---------- ⑥ /appUav/add 去匿名 ----------

    @Test
    @DisplayName("/appUav/add 匿名请求 401")
    void appUavAddAnonymousRejected() throws Exception {
        mockMvc.perform(post("/appUav/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uavName\":\"" + UniqueNames.unique("n") + "\",\"onlineStatus\":\"1\",\"djiId\":\""
                                + UniqueNames.djiId() + "\",\"controllerModel\":\"m\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/appUav/add 登录后可注册无人机")
    void appUavAddAuthenticatedOk() throws Exception {
        TestAccounts.Account user = accounts().registerUser();
        mockMvc.perform(post("/appUav/add")
                        .header("Authorization", user.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uavName\":\"" + UniqueNames.unique("n2") + "\",\"onlineStatus\":\"1\",\"djiId\":\""
                                + UniqueNames.djiId() + "\",\"controllerModel\":\"m\"}"))
                .andExpect(status().isOk());
    }

    // ---------- ⑦ 聊天读历史/撤回越权 ----------

    private record ChatFixture(Long sessionId, String msgId, TestAccounts.Account member, TestAccounts.Account outsider) { }

    private ChatFixture newChatFixture() {
        TestAccounts.Account member = accounts().registerUser();
        TestAccounts.Account outsider = accounts().registerUser();
        long now = System.currentTimeMillis();
        ChatSession session = ChatSession.builder()
                .name(UniqueNames.unique("s"))
                .type(1)
                .ownerId(member.id())
                .userIds(List.of(member.id()))
                .createTime(now)
                .build();
        chatSessionRepository.save(session);
        chatUserSessionRepository.save(ChatUserSession.builder()
                .sessionId(session.getId())
                .userId(member.id())
                .joinTime(now)
                .lastReadTime(0L)
                .build());
        String msgId = UniqueNames.unique("msg");
        chatMessageRepository.save(ChatMessage.builder()
                .msgId(msgId)
                .fromUserId(member.id())
                .sessionId(session.getId())
                .content("hello")
                .status(0)
                .createTime(now)
                .deletedByUserIds(new ArrayList<>())
                .build());
        return new ChatFixture(session.getId(), msgId, member, outsider);
    }

    @Test
    @DisplayName("聊天历史：非会话成员 403，成员 200")
    void chatHistoryMemberOnly() throws Exception {
        ChatFixture fixture = newChatFixture();

        mockMvc.perform(get("/chat/Message/messages/" + fixture.sessionId())
                        .header("Authorization", fixture.outsider().authorization()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/chat/Message/messages/" + fixture.sessionId())
                        .header("Authorization", fixture.member().authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].payload.text").value("hello"));
    }

    @Test
    @DisplayName("聊天撤回：非发送者 403，发送者 200")
    void chatRecallSenderOnly() throws Exception {
        ChatFixture fixture = newChatFixture();

        mockMvc.perform(post("/chat/Message/recall/" + fixture.msgId())
                        .header("Authorization", fixture.outsider().authorization()))
                .andExpect(status().isForbidden());

        Integer statusAfterOutsider = chatMessageRepository.findByMsgId(fixture.msgId())
                .orElseThrow()
                .getStatus();
        assertThat(statusAfterOutsider).isEqualTo(0);

        mockMvc.perform(post("/chat/Message/recall/" + fixture.msgId())
                        .header("Authorization", fixture.member().authorization()))
                .andExpect(status().isOk());

        Integer statusAfterSender = chatMessageRepository.findByMsgId(fixture.msgId())
                .orElseThrow()
                .getStatus();
        assertThat(statusAfterSender).isEqualTo(2);
    }

    // ---------- ⑨ /live/get 身份取登录态 ----------

    @Test
    @DisplayName("/live/get：客户端传入 webUserId 不参与签名，身份改为登录用户")
    void liveGetIdentityFromContext() throws Exception {
        TestAccounts.Account user = accounts().registerUser();
        String deviceId = UniqueNames.unique("live-dev");

        Uav uav = new Uav();
        uav.setUavName(UniqueNames.unique("ln"));
        uav.setDjiId(deviceId);
        uav.setOnlineStatus('1');
        uav.setControllerModel("cm");
        uav.setIsAvailable('1');
        uavRepository.save(uav);

        appWebSocketService.requestConnection(deviceId);
        appWebSocketService.markAsConnected(deviceId);

        mockMvc.perform(post("/live/get")
                        .param("deviceId", deviceId)
                        .param("webUserId", "999888")   // 攻击者尝试冒充 TRTC 身份 999888
                        .header("Authorization", user.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(String.valueOf(user.id())))
                .andExpect(jsonPath("$.data.userSig").isNotEmpty());
    }

    // ---------- /api/ws/request 需登录 + 设备归属 ----------

    @Test
    @DisplayName("/api/ws/request：匿名 401、未绑定飞手 403、绑定飞手 200")
    void wsRequestRequiresLoginAndOwnership() throws Exception {
        mockMvc.perform(post("/api/ws/request").param("deviceId", UniqueNames.unique("req-a")))
                .andExpect(status().isUnauthorized());

        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("rq"), null);
        mockMvc.perform(post("/api/ws/request")
                        .param("deviceId", UniqueNames.unique("req-b"))
                        .header("Authorization", rider.authorization()))
                .andExpect(status().isForbidden());

        TestAccounts.Account boundRider = accounts().registerRider(UniqueNames.userName("rq2"), null);
        String boundDevice = UniqueNames.unique("req-c");
        RiderUav binding = new RiderUav();
        binding.setUserId(boundRider.id());
        binding.setDjiId(boundDevice);
        riderUavRepository.save(binding);

        mockMvc.perform(post("/api/ws/request")
                        .param("deviceId", boundDevice)
                        .header("Authorization", boundRider.authorization()))
                .andExpect(status().isOk());
    }
}
