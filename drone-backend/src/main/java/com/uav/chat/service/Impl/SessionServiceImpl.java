package com.uav.chat.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import com.uav.chat.pojo.dto.SessionDTO;
import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.pojo.vo.SessionVO;
import com.uav.chat.repository.ChatMessageRepository;
import com.uav.chat.repository.ChatSessionRepository;
import com.uav.chat.service.SessionService;
import com.uav.chat.service.UserSessionService;
import com.uav.server.util.UserContext;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SessionServiceImpl implements SessionService {

    private final ChatSessionRepository chatSessionRepository;
    private final UserSessionService userSessionService;
    private final UserRepository userRepository;
    private final ChatMessageRepository chatMessageRepository;

    public SessionServiceImpl(ChatSessionRepository chatSessionRepository,
                              UserSessionService userSessionService,
                              UserRepository userRepository,
                              ChatMessageRepository chatMessageRepository) {
        this.chatSessionRepository = chatSessionRepository;
        this.userSessionService = userSessionService;
        this.userRepository = userRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    @Override
    @Transactional
    public SessionVO createSession(SessionDTO dto) {
        Long userId = UserContext.getUserId();
        List<Long> ids = new java.util.ArrayList<>(dto.getUserIds());
        ids.add(userId);
        List<Long> distinctIds = ids.stream().distinct().toList();

        if (dto.getType() != null && dto.getType() == 0 && distinctIds.size() == 2) {
            List<SessionVO> existing = listSession();
            for (SessionVO s : existing) {
                if (s.getType() != null && s.getType() == 0) {
                    List<Long> sIds = getUserIdsBySessionId(s.getId());
                    if (sIds != null && sIds.size() == 2
                            && sIds.containsAll(distinctIds)) {
                        return s;
                    }
                }
            }
        }

        ChatSession session = BeanUtil.copyProperties(dto, ChatSession.class);
        session.setUserIds(distinctIds);
        long currentTimeMillis = System.currentTimeMillis();
        session.setCreateTime(currentTimeMillis);
        session.setOwnerId(userId);
        chatSessionRepository.saveAndFlush(session);

        List<ChatUserSession> chatUserSessions = distinctIds.stream()
                .map(id -> ChatUserSession.builder()
                        .sessionId(session.getId())
                        .userId(id)
                        .joinTime(currentTimeMillis)
                        .lastReadTime(currentTimeMillis)
                        .build())
                .collect(Collectors.toList());
        userSessionService.saveAll(chatUserSessions);

        SessionVO vo = BeanUtil.copyProperties(session, SessionVO.class);
        vo.setUserIds(distinctIds);

        if (dto.getType() != null && dto.getType() == 0 && distinctIds.size() == 2) {
            Long otherId = distinctIds.stream().filter(id -> !id.equals(userId)).findFirst().orElse(null);
            if (otherId != null) {
                userRepository.findById(otherId).ifPresent(u -> vo.setOtherUserName(u.getUserName()));
            }
        }
        return vo;
    }

    @Override
    @Transactional
    public String deleteSession(Long sessionId) {
        ChatSession session = chatSessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return "会话不存在";
        }
        Long ownerId = session.getOwnerId();
        Long userId = UserContext.getUserId();
        if (ownerId != null && Objects.equals(ownerId, userId)) {
            chatSessionRepository.deleteById(sessionId);
            return "会话已删除";
        }
        return "只有群主可以删除会话";
    }

    @Override
    public List<SessionVO> listSession() {
        Long userId = UserContext.getUserId();

        List<ChatUserSession> userSessions = userSessionService.findByUserId(userId);
        if (userSessions.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> sessionIds = userSessions.stream()
                .map(ChatUserSession::getSessionId)
                .collect(Collectors.toList());
        List<ChatSession> sessions = chatSessionRepository.findAllById(sessionIds);

        Map<Long, ChatSession> sessionMap = sessions.stream()
                .collect(Collectors.toMap(ChatSession::getId, Function.identity()));

        List<ChatUserSession> allMembers = userSessionService.findBySessionIdIn(sessionIds);
        Map<Long, List<Long>> memberMap = allMembers.stream()
                .collect(Collectors.groupingBy(
                        ChatUserSession::getSessionId,
                        Collectors.mapping(ChatUserSession::getUserId, Collectors.toList())));

        Map<Long, Long> lastReadMap = userSessions.stream()
                .collect(Collectors.toMap(ChatUserSession::getSessionId, us -> {
                    Long lrt = us.getLastReadTime();
                    return lrt != null ? lrt : us.getJoinTime();
                }));

        return userSessions.stream()
                .map(ChatUserSession::getSessionId)
                .distinct()
                .map(sessionId -> {
                    ChatSession session = sessionMap.get(sessionId);
                    if (session == null) {
                        return null;
                    }
                    SessionVO vo = BeanUtil.copyProperties(session, SessionVO.class);
                    List<Long> memberIds = memberMap.getOrDefault(sessionId, Collections.emptyList());
                    vo.setUserIds(memberIds);
                    if (session.getType() != null && session.getType() == 0 && memberIds.size() == 2) {
                        Long otherId = memberIds.stream().filter(id -> !id.equals(userId)).findFirst().orElse(null);
                        if (otherId != null) {
                            User otherUser = userRepository.findById(otherId).orElse(null);
                            if (otherUser != null) {
                                vo.setOtherUserName(otherUser.getUserName());
                            }
                        }
                    }
                    ChatMessage lastMsg = chatMessageRepository
                            .findFirstBySessionIdOrderByCreateTimeDesc(sessionId)
                            .orElse(null);
                    if (lastMsg != null) {
                        vo.setLastMessage(lastMsg.getContent());
                        vo.setLastMessageTime(lastMsg.getCreateTime());
                    }
                    Long since = lastReadMap.get(sessionId);
                    if (since != null) {
                        vo.setUnreadCount(chatMessageRepository.countUnread(sessionId, since, userId));
                    } else {
                        vo.setUnreadCount(0);
                    }
                    return vo;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<Long> getUserIdsBySessionId(Long sessionId) {
        return userSessionService.findBySessionId(sessionId).stream()
                .map(ChatUserSession::getUserId)
                .collect(Collectors.toList());
    }
}
