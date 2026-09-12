package com.uav.chat;

import com.uav.chat.mapper.ChatUserSessionMapper;
import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.service.MessageService;
import com.uav.server.exception.BusinessException;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.util.JwtUtil;
import com.uav.server.util.UserContext;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * t49 契约测试（BE P3-37 语义修复）：
 * ① WS 上线补推（getUnreadMessages）只拉不推 lastReadTime——补推消息保留未读语义；
 * ② 补推不计入未读重复累加（未读数以 lastReadTime 单源计算，幂等）；
 * ③ 显式 markSessionRead 推进已读，仅会话成员可调用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
class UnreadSemanticsTest {

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ChatUserSessionMapper chatUserSessionMapper;

    @Autowired
    com.uav.chat.mapper.ChatSessionMapper chatSessionMapper;

    @Autowired
    com.uav.chat.mapper.ChatMessageMapper chatMessageMapper;

    @Autowired
    MessageService messageService;

    @Autowired
    org.springframework.web.context.WebApplicationContext wac;

    private long rid;

    @BeforeEach
    void setUp() {
        rid = System.nanoTime();
    }

    @AfterEach
    void restoreContext() {
        UserContext.clear();
    }

    private User newUser(int role) {
        User user = new User();
        user.setUserName("unr" + rid + "-" + role + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 6));
        user.setPassword("irrelevant");
        user.setStatus(1);
        user.setRole(role);
        return userRepository.save(user);
    }

    /** 建一个 owner+rider 双成员会话；成员 lastReadTime=0（历史未读全量）。返回 sessionId。 */
    private Long seedSessionWithMembers(User a, User b) {
        long now = System.currentTimeMillis();
        com.uav.chat.pojo.entity.ChatSession session = com.uav.chat.pojo.entity.ChatSession.builder()
                .name("s-" + rid)
                .type(0)
                .ownerId(a.getId())
                .userIds(List.of(a.getId(), b.getId()))
                .createTime(now)
                .build();
        chatSessionMapper.insert(session);
        for (User u : List.of(a, b)) {
            chatUserSessionMapper.insert(ChatUserSession.builder()
                    .sessionId(session.getId())
                    .userId(u.getId())
                    .joinTime(now)
                    .lastReadTime(0L)
                    .build());
        }
        return session.getId();
    }

    private void insertMessage(Long sessionId, Long fromUserId, String content, long createTime) {
        chatMessageMapper.insert(ChatMessage.builder()
                .msgId("msg-" + rid + "-" + UUID.randomUUID().toString().substring(0, 8))
                .fromUserId(fromUserId)
                .sessionId(sessionId)
                .content(content)
                .status(0)
                .createTime(createTime)
                .deletedByUserIds(new java.util.ArrayList<>())
                .build());
    }

    private int unreadCount(Long userId, Long sessionId) {
        return messageService.getUnreadCountMap(userId).getOrDefault(sessionId, 0);
    }

    @Test
    @DisplayName("① 补推保留未读：getUnreadMessages 拉取后 lastReadTime 不变、未读数不消失")
    void offlinePullKeepsUnreadSemantics() {
        User a = newUser(0);
        User b = newUser(1);
        Long sessionId = seedSessionWithMembers(a, b);
        insertMessage(sessionId, b.getId(), "离线消息 1", System.currentTimeMillis() - 5_000);

        // WS 上线补推 = getUnreadMessages：拉到消息
        var pulled = messageService.getUnreadMessages(a.getId());
        assertThat(pulled).hasSize(1);

        // 但 lastReadTime 未被推进（成员行不变，仍为 0）
        ChatUserSession link = chatUserSessionMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ChatUserSession>lambdaQuery()
                        .eq(ChatUserSession::getSessionId, sessionId)
                        .eq(ChatUserSession::getUserId, a.getId()));
        assertThat(link.getLastReadTime()).isEqualTo(0L);

        // 未读语义保留：计数仍为 1
        assertThat(unreadCount(a.getId(), sessionId)).isEqualTo(1);

        // 重复补拉（连接重试/再次 sync）：同一消息再拉一次，未读数不重复累加
        var pulledAgain = messageService.getUnreadMessages(a.getId());
        assertThat(pulledAgain).hasSize(1);
        assertThat(unreadCount(a.getId(), sessionId)).isEqualTo(1);
    }

    @Test
    @DisplayName("② 显式已读：markSessionRead 推进 lastReadTime，未读数清零；新消息重新计入")
    void explicitMarkSessionReadAdvances() {
        User a = newUser(0);
        User b = newUser(1);
        Long sessionId = seedSessionWithMembers(a, b);
        insertMessage(sessionId, b.getId(), "msg-1", System.currentTimeMillis() - 5_000);

        assertThat(unreadCount(a.getId(), sessionId)).isEqualTo(1);

        messageService.markSessionRead(a.getId(), sessionId);

        assertThat(unreadCount(a.getId(), sessionId)).isZero();

        // 显式已读后，sync 补拉不再返回旧消息
        assertThat(messageService.getUnreadMessages(a.getId())).isEmpty();

        // 新消息到达 → 重新计入未读
        insertMessage(sessionId, b.getId(), "msg-2", System.currentTimeMillis());
        assertThat(unreadCount(a.getId(), sessionId)).isEqualTo(1);
    }

    @Test
    @DisplayName("③ 权限：非会话成员 markSessionRead → 403 NO_PERMISSION")
    void markSessionReadRequiresMembership() {
        User member = newUser(0);
        User b = newUser(0);
        Long sessionId = seedSessionWithMembers(member, b);
        User outsider = newUser(0); // 不在会话内
        insertMessage(sessionId, member.getId(), "hello", System.currentTimeMillis());

        UserContext.setUser(outsider.getId(), outsider.getUserName(), 0);
        assertThatThrownBy(() -> messageService.markSessionRead(outsider.getId(), sessionId))
                .isInstanceOf(com.uav.server.exception.BusinessException.class)
                .extracting("code")
                .isEqualTo(ApiErrorCode.NO_PERMISSION.getCode());
    }

    @Test
    @DisplayName("④ MockMvc：POST /chat/Message/read 显式推进已读（成员 200， outsider 403）")
    void readEndpointContract() throws Exception {
        User member = newUser(0);
        User b = newUser(0);
        Long sessionId = seedSessionWithMembers(member, b);
        User outsider = newUser(0); // 不在会话内
        insertMessage(sessionId, member.getId(), "hello", System.currentTimeMillis());

        var mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(wac).build();

        String memberToken = "Bearer " + jwtUtil.generateToken(
                member.getId(), member.getUserName(), member.getRole());
        String outsiderToken = "Bearer " + jwtUtil.generateToken(
                outsider.getId(), outsider.getUserName(), outsider.getRole());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/chat/Message/read")
                        .param("sessionId", String.valueOf(sessionId))
                        .header("Authorization", memberToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/chat/Message/read")
                        .param("sessionId", String.valueOf(sessionId))
                        .header("Authorization", outsiderToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
}
