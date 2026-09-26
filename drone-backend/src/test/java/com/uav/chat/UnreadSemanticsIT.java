package com.uav.chat;

import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.repository.ChatMessageRepository;
import com.uav.chat.repository.ChatSessionRepository;
import com.uav.chat.repository.ChatUserSessionRepository;
import com.uav.chat.service.MessageService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.util.UserContext;
import com.uav.support.IntegrationTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UnreadSemanticsIT extends IntegrationTestBase {

    @Autowired
    private ChatUserSessionRepository chatUserSessionRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private MessageService messageService;

    private Long seedSessionWithMembers(TestAccounts.Account a, TestAccounts.Account b) {
        long now = System.currentTimeMillis();
        ChatSession session = ChatSession.builder()
                .name(UniqueNames.unique("s"))
                .type(0)
                .ownerId(a.id())
                .userIds(List.of(a.id(), b.id()))
                .createTime(now)
                .build();
        chatSessionRepository.save(session);
        for (TestAccounts.Account u : List.of(a, b)) {
            chatUserSessionRepository.save(ChatUserSession.builder()
                    .sessionId(session.getId())
                    .userId(u.id())
                    .joinTime(now)
                    .lastReadTime(0L)
                    .build());
        }
        return session.getId();
    }

    private void insertMessage(Long sessionId, Long fromUserId, String content, long createTime) {
        chatMessageRepository.save(ChatMessage.builder()
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

        var pulled = messageService.getUnreadMessages(a.id());
        assertThat(pulled).hasSize(1);

        ChatUserSession link = chatUserSessionRepository.findBySessionIdAndUserId(sessionId, a.id())
                .orElseThrow();
        assertThat(link.getLastReadTime()).isEqualTo(0L);

        assertThat(unreadCount(a.id(), sessionId)).isEqualTo(1);

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
        assertThat(messageService.getUnreadMessages(a.id())).isEmpty();

        insertMessage(sessionId, b.id(), "msg-2", System.currentTimeMillis());
        assertThat(unreadCount(a.id(), sessionId)).isEqualTo(1);
    }

    @Test
    @DisplayName("③ 权限：非会话成员 markSessionRead → 403 NO_PERMISSION")
    void markSessionReadRequiresMembership() {
        TestAccounts.Account member = accounts().registerUser();
        TestAccounts.Account b = accounts().registerUser();
        Long sessionId = seedSessionWithMembers(member, b);
        TestAccounts.Account outsider = accounts().registerUser();
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
        TestAccounts.Account outsider = accounts().registerUser();
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
