package com.uav.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.uav.chat.mapper.ChatMessageMapper;
import com.uav.chat.mapper.ChatSessionMapper;
import com.uav.chat.mapper.ChatUserSessionMapper;
import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.live.service.AppWebSocketService;
import com.uav.server.util.JwtUtil;
import com.uav.uav.mapper.UavRepository;
import com.uav.uav.pojo.entity.Uav;
import com.uav.user.mapper.RiderUavRepository;
import com.uav.user.mapper.UserRecordRepository;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.RiderUav;
import com.uav.user.pojo.entity.User;
import com.uav.user.pojo.entity.UserRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0-2/4/5/6/7/9 防护测试（HTTP 层越权场景全部被拒）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HttpAccessControlTest {

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
    ChatSessionMapper chatSessionMapper;

    @Autowired
    ChatUserSessionMapper chatUserSessionMapper;

    @Autowired
    ChatMessageMapper chatMessageMapper;

    @Autowired
    AppWebSocketService appWebSocketService;

    MockMvc mockMvc;

    private final List<Long> userIds = new ArrayList<>();

    private long rid;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        rid = System.nanoTime();
    }

    @AfterEach
    void cleanUp() {
        // 测试用户清理（上下文共享 H2，避免影响其它用例的唯一约束）
        userIds.forEach(id -> userRepository.findById(id).ifPresent(userRepository::delete));
    }

    // ---------- ② 管理端点角色门 ----------

    @Test
    @DisplayName("/admin/uav 普通用户访问 403")
    void adminUavForbiddenForNormalUser() throws Exception {
        User user = newUser("nu" + rid, 0);
        mockMvc.perform(get("/admin/uav").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/admin/uav 飞手访问 403")
    void adminUavForbiddenForRider() throws Exception {
        User rider = newUser("rid" + rid, 1);
        mockMvc.perform(get("/admin/uav/statistics").header("Authorization", bearer(rider)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/admin/uav 管理员访问 200")
    void adminUavOkForAdmin() throws Exception {
        User admin = newUser("ad" + rid, 2);
        mockMvc.perform(get("/admin/uav").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("/admin/logs 普通用户访问 403（日志泄露防护）")
    void adminLogsForbiddenForNormalUser() throws Exception {
        User user = newUser("nu2" + rid, 0);
        mockMvc.perform(get("/admin/logs/files").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/logs/application").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/logs/error").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/admin/logs/files 管理员访问 200")
    void adminLogsOkForAdmin() throws Exception {
        User admin = newUser("ad2" + rid, 2);
        mockMvc.perform(get("/admin/logs/files").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    // ---------- ⑤ 观看记录限制本人 ----------

    @Test
    @DisplayName("/webUav/getRecord 普通用户传他人 userName 时强制查自己")
    void getRecordForcedToSelf() throws Exception {
        User other = newUser("other" + rid, 0);
        UserRecord otherRecord = new UserRecord();
        otherRecord.setUserName(other.getUserName());
        otherRecord.setDjiId("dev-" + rid);
        otherRecord.setStart_time(java.time.LocalDateTime.now());
        userRecordRepository.save(otherRecord);

        User self = newUser("self" + rid, 0);
        // self 显式传了 other 的 userName，但服务端必须按登录态查询 → 结果为空
        mockMvc.perform(get("/webUav/getRecord")
                        .param("userName", other.getUserName())
                        .header("Authorization", bearer(self)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(0));

        // 自查本人记录 → 能看到自己的一条
        UserRecord selfRecord = new UserRecord();
        selfRecord.setUserName(self.getUserName());
        selfRecord.setDjiId("dev-" + rid);
        selfRecord.setStart_time(java.time.LocalDateTime.now());
        userRecordRepository.save(selfRecord);

        mockMvc.perform(get("/webUav/getRecord")
                        .param("userName", other.getUserName())
                        .header("Authorization", bearer(self)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("/webUav/getRecord 管理员可代查他人记录")
    void getRecordAdminOverride() throws Exception {
        User other = newUser("other2" + rid, 0);
        UserRecord record = new UserRecord();
        record.setUserName(other.getUserName());
        record.setDjiId("dev2-" + rid);
        record.setStart_time(java.time.LocalDateTime.now());
        userRecordRepository.save(record);

        User admin = newUser("ad3" + rid, 2);
        mockMvc.perform(get("/webUav/getRecord")
                        .param("userName", other.getUserName())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    // ---------- ⑥ /appUav/add 去匿名 ----------

    @Test
    @DisplayName("/appUav/add 匿名请求 401")
    void appUavAddAnonymousRejected() throws Exception {
        mockMvc.perform(post("/appUav/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uavName\":\"n" + rid + "\",\"onlineStatus\":\"1\",\"djiId\":\"d" + rid
                                + "\",\"controllerModel\":\"m\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/appUav/add 登录后可注册无人机")
    void appUavAddAuthenticatedOk() throws Exception {
        User user = newUser("uavowner" + rid, 0);
        mockMvc.perform(post("/appUav/add")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uavName\":\"n2" + rid + "\",\"onlineStatus\":\"1\",\"djiId\":\"d2" + rid
                                + "\",\"controllerModel\":\"m\"}"))
                .andExpect(status().isOk());
    }

    // ---------- ⑦ 聊天读历史/撤回越权 ----------

    private record ChatFixture(Long sessionId, String msgId, User member, User outsider) { }

    private ChatFixture newChatFixture() {
        User member = newUser("cm" + rid, 0);
        User outsider = newUser("co" + rid, 0);
        long now = System.currentTimeMillis();
        ChatSession session = ChatSession.builder()
                .name("s" + rid)
                .type(1)
                .ownerId(member.getId())
                .userIds(List.of(member.getId()))
                .createTime(now)
                .build();
        chatSessionMapper.insert(session);
        chatUserSessionMapper.insert(ChatUserSession.builder()
                .sessionId(session.getId())
                .userId(member.getId())
                .joinTime(now)
                .lastReadTime(0L)
                .build());
        String msgId = "msg-" + rid;
        chatMessageMapper.insert(ChatMessage.builder()
                .msgId(msgId)
                .fromUserId(member.getId())
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
                        .header("Authorization", bearer(fixture.outsider())))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/chat/Message/messages/" + fixture.sessionId())
                        .header("Authorization", bearer(fixture.member())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].payload.text").value("hello"));
    }

    @Test
    @DisplayName("聊天撤回：非发送者 403，发送者 200")
    void chatRecallSenderOnly() throws Exception {
        ChatFixture fixture = newChatFixture();

        mockMvc.perform(post("/chat/Message/recall/" + fixture.msgId())
                        .header("Authorization", bearer(fixture.outsider())))
                .andExpect(status().isForbidden());

        Integer statusAfterOutsider = chatMessageMapper
                .selectOne(Wrappers.<ChatMessage>lambdaQuery().eq(ChatMessage::getMsgId, fixture.msgId()))
                .getStatus();
        assertThat(statusAfterOutsider).isEqualTo(0);

        mockMvc.perform(post("/chat/Message/recall/" + fixture.msgId())
                        .header("Authorization", bearer(fixture.member())))
                .andExpect(status().isOk());

        Integer statusAfterSender = chatMessageMapper
                .selectOne(Wrappers.<ChatMessage>lambdaQuery().eq(ChatMessage::getMsgId, fixture.msgId()))
                .getStatus();
        assertThat(statusAfterSender).isEqualTo(2);
    }

    // ---------- ⑨ /live/get 身份取登录态 ----------

    @Test
    @DisplayName("/live/get：客户端传入 webUserId 不参与签名，身份改为登录用户")
    void liveGetIdentityFromContext() throws Exception {
        User user = newUser("lv" + rid, 0);
        String deviceId = "live-dev-" + rid;

        Uav uav = new Uav();
        uav.setUavName("ln" + rid);
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
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(String.valueOf(user.getId())))
                .andExpect(jsonPath("$.data.userSig").isNotEmpty());
    }

    // ---------- /api/ws/request 需登录 + 设备归属 ----------

    @Test
    @DisplayName("/api/ws/request：匿名 401、未绑定飞手 403、绑定飞手 200")
    void wsRequestRequiresLoginAndOwnership() throws Exception {
        mockMvc.perform(post("/api/ws/request").param("deviceId", "req-a-" + rid))
                .andExpect(status().isUnauthorized());

        User rider = newUser("rq" + rid, 1);
        mockMvc.perform(post("/api/ws/request")
                        .param("deviceId", "req-b-" + rid)
                        .header("Authorization", bearer(rider)))
                .andExpect(status().isForbidden());

        User boundRider = newUser("rq2" + rid, 1);
        String boundDevice = "req-c-" + rid;
        RiderUav binding = new RiderUav();
        binding.setUserId(boundRider.getId());
        binding.setDjiId(boundDevice);
        riderUavRepository.save(binding);

        mockMvc.perform(post("/api/ws/request")
                        .param("deviceId", boundDevice)
                        .header("Authorization", bearer(boundRider)))
                .andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private User newUser(String name, int role) {
        User user = new User();
        user.setUserName(name);
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        user = userRepository.save(user);
        userIds.add(user.getId());
        return user;
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.generateToken(user.getId(), user.getUserName(), user.getRole());
    }
}
