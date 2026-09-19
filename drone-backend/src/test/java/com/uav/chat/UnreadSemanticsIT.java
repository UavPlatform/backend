package com.uav.chat;

import com.uav.chat.mapper.ChatMessageMapper;
import com.uav.chat.mapper.ChatSessionMapper;
import com.uav.chat.mapper.ChatUserSessionMapper;
import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.service.MessageService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约测试（t49 / BE P3-37 语义修复）：
 * ① WS 上线补推（getUnreadMessages）只拉不推 lastReadTime——补推消息保留未读语义；
 * ② 补推不计入未读重复累加（未读数以 lastReadTime 单源计算，幂等）；
 * ③ 显式 markSessionRead 推进已读，仅会话成员可调用。MockMvc 集成测试（R9/O4）。
 *
 * <p>R2/R4：继承 {@link IntegrationTestBase}（MOCK + {@code @AutoConfigureMockMvc}），
 * 删除无真实端口依据的 {@code RANDOM_PORT}；R3：成员身份由 {@link TestAccounts}
 * 真实注册取得，不再自签 token；R5：{@code System.nanoTime()} 唯一名改为 {@code UniqueNames}，
 * 隔离靠事务回滚。
 */
class UnreadSemanticsIT extends IntegrationTestBase {

    @Autowired
    private ChatUserSessionMapper chatUserSessionMapper;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private MessageService messageService;

    /** 建一个双成员会话；成员 lastReadTime=0（历史未读全量）。返回 sessionId。 */
    private Long seedSessionWithMembers(TestAccounts.Account a, TestAccounts.Account b) {
        long now = System.currentTimeMillis();
        ChatSession session = ChatSession.builder()
                .name(UniqueNames.unique("s"))
                .type(0)
                .ownerId(a.id())
                .userIds(List.of(a.id(), b.id()))
                .createTime(now)
                .build();
        chatSessionMapper.insert(session);
        for (TestAccounts.Account u : List.of(a, b)) {
            chatUserSessionMapper.insert(ChatUserSession.builder()
                    .sessionId(session.getId())
                    .userId(u.id())
                    .joinTime(now)
                    .lastReadTime(0L)
                    .build());
        }
        return session.getId();
    }

    private void insertMessage(Long sessionId, Long fromUserId, String content, long createTime) {
        chatMessageMapper.insert(ChatMessage.builder()
                .msgId(UniqueNames.unique("msg"))
                .fromUserId(fromUserId)
                .sessionId(sessionId)
                .content(content)
                .status(0)
                .createTime(createTime)
                .deletedByUserIds(new ArrayList<>())
                .build());
    }

    private int unreadCount(Long userId, Long sessionId) {
        return messageService.getUnreadCountMap(userId).getOrDefault(sessionId, 0);
    }

    @Test
    @DisplayName("① 补推保留未读：getUnreadMessages 拉取后 lastReadTime 不变、未读数不消失")
    void offlinePullKeepsUnreadSemantics() {
        TestAccounts.Account a = accounts().registerUser();
        TestAccounts.Account b = accounts().registerUser();
        Long sessionId = seedSessionWithMembers(a, b);
        insertMessage(sessionId, b.id(), "离线消息 1", System.currentTimeMillis() - 5_000);

        // WS 上线补推 = getUnreadMessages：拉到消息
        var pulled = messageService.getUnreadMessages(a.id());
        assertThat(pulled).hasSize(1);

        // 但 lastReadTime 未被推进（成员行不变，仍为 0）
        ChatUserSession link = chatUserSessionMapper.selectOne(
                Wrappers.<ChatUserSession>lambdaQuery()
                        .eq(ChatUserSession::getSessionId, sessionId)
                        .eq(ChatUserSession::getUserId, a.id()));
        assertThat(link.getLastReadTime()).isEqualTo(0L);

        // 未读语义保留：计数仍为 1
        assertThat(unreadCount(a.id(), sessionId)).isEqualTo(1);

        // 重复补拉（连接重试/再次 sync）：同一消息再拉一次，未读数不重复累加
        var pulledAgain = messageService.getUnreadMessages(a.id());
        assertThat(pulledAgain).hasSize(1);
        assertThat(unreadCount(a.id(), sessionId)).isEqualTo(1);
    }

    @Test
    @DisplayName("② 显式已读：markSessionRead 推进 lastReadTime，未读数清零；新消息重新计入")
    void explicitMarkSessionReadAdvances() {
        TestAccounts.Account a = accounts().registerUser();
        TestAccounts.Account b = accounts().registerUser();
        Long sessionId = seedSessionWithMembers(a, b);
        insertMessage(sessionId, b.id(), "msg-1", System.currentTimeMillis() - 5_000);

        assertThat(unreadCount(a.id(), sessionId)).isEqualTo(1);

        messageService.markSessionRead(a.id(), sessionId);

        assertThat(unreadCount(a.id(), sessionId)).isZero();

        // 显式已读后，sync 补拉不再返回旧消息
        assertThat(messageService.getUnreadMessages(a.id())).isEmpty();

        // 新消息到达 → 重新计入未读
        insertMessage(sessionId, b.id(), "msg-2", System.currentTimeMillis());
        assertThat(unreadCount(a.id(), sessionId)).isEqualTo(1);
    }

    @Test
    @DisplayName("③ 权限：非会话成员 markSessionRead → 403 NO_PERMISSION")
    void markSessionReadRequiresMembership() {
        TestAccounts.Account member = accounts().registerUser();
        TestAccounts.Account b = accounts().registerUser();
        Long sessionId = seedSessionWithMembers(member, b);
        TestAccounts.Account outsider = accounts().registerUser(); // 不在会话内
        insertMessage(sessionId, member.id(), "hello", System.currentTimeMillis());

        UserContext.setUser(outsider.id(), outsider.userName(), outsider.role());
        assertThatThrownBy(() -> messageService.markSessionRead(outsider.id(), sessionId))
                .isInstanceOf(com.uav.server.exception.BusinessException.class)
                .extracting("code")
                .isEqualTo(ApiErrorCode.NO_PERMISSION.getCode());
    }

    @Test
    @DisplayName("④ MockMvc：POST /chat/Message/read 显式推进已读（成员 200， outsider 403）")
    void readEndpointContract() throws Exception {
        TestAccounts.Account member = accounts().registerUser();
        TestAccounts.Account b = accounts().registerUser();
        Long sessionId = seedSessionWithMembers(member, b);
        TestAccounts.Account outsider = accounts().registerUser(); // 不在会话内
        insertMessage(sessionId, member.id(), "hello", System.currentTimeMillis());

        mockMvc.perform(post("/chat/Message/read")
                        .param("sessionId", String.valueOf(sessionId))
                        .header("Authorization", member.authorization()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/chat/Message/read")
                        .param("sessionId", String.valueOf(sessionId))
                        .header("Authorization", outsider.authorization()))
                .andExpect(status().isForbidden());
    }
}
