package com.uav.chat.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.uav.chat.mapper.ChatMessageMapper;
import com.uav.chat.mapper.ChatSessionMapper;
import com.uav.chat.pojo.dto.SessionDTO;
import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.pojo.vo.SessionVO;
import com.uav.chat.service.SessionService;
import com.uav.chat.service.UserSessionService;
import com.uav.server.util.UserContext;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements SessionService {
    @Autowired
    private UserSessionService userSessionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

//    @Transactional(rollbackFor = Exception.class)
    @Override
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
        super.save(session);
        List<ChatUserSession> chatUserSessions = distinctIds.stream()
                .map(id -> ChatUserSession.builder()
                        .sessionId(session.getId())
                        .userId(id)
                        .joinTime(currentTimeMillis)
                        .lastReadTime(currentTimeMillis)
                        .build())
                .collect(Collectors.toList());
        userSessionService.saveBatch(chatUserSessions);

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
    public String deleteSession(Long sessionId) {
        String respStr = "";
        ChatSession session = super.getById(sessionId);
        if (session == null) {
            respStr = "会话不存在";
            return respStr;
        }
        Long ownerId = session.getOwnerId();
        Long userId = UserContext.getUserId();
        if (ownerId != null && Objects.equals(ownerId, userId)) {
            respStr = super.removeById(sessionId) ? "会话已删除" : "会话删除失败";
        }
        else {
            respStr = "只有群主可以删除会话";
        }
        return respStr;
    }

    @Override
    public List<SessionVO> listSession() {
        Long userId = UserContext.getUserId();

        // 1. 查询当前用户参与的会话关联记录
        LambdaQueryWrapper<ChatUserSession> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ChatUserSession::getUserId, userId);
        List<ChatUserSession> userSessions = userSessionService.list(wrapper);
        if (userSessions.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 批量查询会话详情
        List<Long> sessionIds = userSessions.stream()
                .map(ChatUserSession::getSessionId)
                .collect(Collectors.toList());
        List<ChatSession> sessions = baseMapper.selectBatchIds(sessionIds); // 使用 Mapper 批量查询

        // 3. 转换成 Map 方便匹配
        Map<Long, ChatSession> sessionMap = sessions.stream()
                .collect(Collectors.toMap(ChatSession::getId, Function.identity()));

        // 4. 查询这些会话的所有成员
        LambdaQueryWrapper<ChatUserSession> memberWrapper = Wrappers.lambdaQuery();
        memberWrapper.in(ChatUserSession::getSessionId, sessionIds);
        List<ChatUserSession> allMembers = userSessionService.list(memberWrapper);
        Map<Long, List<Long>> memberMap = allMembers.stream()
                .collect(Collectors.groupingBy(
                        ChatUserSession::getSessionId,
                        Collectors.mapping(ChatUserSession::getUserId, Collectors.toList())));

        return userSessions.stream()
                .map(ChatUserSession::getSessionId)
                .distinct()
                .map(sessionId -> {
                    ChatSession session = sessionMap.get(sessionId);
                    if (session == null) return null;
                    SessionVO vo = BeanUtil.copyProperties(session, SessionVO.class);
                    List<Long> memberIds = memberMap.getOrDefault(sessionId, Collections.emptyList());
                    vo.setUserIds(memberIds);
                    // 私聊：找对方用户名
                    if (session.getType() != null && session.getType() == 0 && memberIds.size() == 2) {
                        Long otherId = memberIds.stream().filter(id -> !id.equals(userId)).findFirst().orElse(null);
                        if (otherId != null) {
                            User otherUser = userRepository.findById(otherId).orElse(null);
                            if (otherUser != null) vo.setOtherUserName(otherUser.getUserName());
                        }
                    }
                    // 最后一条消息
                    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessage> msgWrapper =
                            Wrappers.<ChatMessage>lambdaQuery()
                                    .eq(ChatMessage::getSessionId, sessionId)
                                    .orderByDesc(ChatMessage::getCreateTime)
                                    .last("LIMIT 1");
                    ChatMessage lastMsg = chatMessageMapper.selectOne(msgWrapper);
                    if (lastMsg != null) {
                        vo.setLastMessage(lastMsg.getContent());
                        vo.setLastMessageTime(lastMsg.getCreateTime());
                    }
                    return vo;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<Long> getUserIdsBySessionId(Long sessionId) {
        LambdaQueryWrapper<ChatUserSession> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(ChatUserSession::getSessionId, sessionId);
        return userSessionService.list(wrapper).stream()
                .map(ChatUserSession::getUserId)
                .collect(Collectors.toList());
    }

}
