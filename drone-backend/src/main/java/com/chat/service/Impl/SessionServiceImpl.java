package com.chat.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.chat.mapper.ChatSessionMapper;
import com.chat.pojo.dto.SessionDTO;
import com.chat.pojo.entity.ChatSession;
import com.chat.pojo.entity.ChatUserSession;
import com.chat.pojo.vo.SessionVO;
import com.chat.service.SessionService;
import com.chat.service.UserSessionService;
import com.drone.server.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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

//    @Transactional(rollbackFor = Exception.class)
    @Override
    public void createSession(SessionDTO dto) {
        // replaced {2L} with {UserContext.getUserId()};
        Long userId = 2L;
        List<Long> ids = dto.getUserIds();

        // 1. 去重并包含创建者
        if (!ids.contains(userId)) {
            ids.add(userId);
        }
        List<Long> distinctIds = ids.stream().distinct().collect(Collectors.toList());

        // 先用这个测试
        List<ChatUserSession> sessions = distinctIds.stream()
                .map(id -> ChatUserSession.builder()
                        .sessionId(null)
                        .userId(id)
                        .joinTime(LocalDateTime.now())
                        .lastReadTime(LocalDateTime.now())
                        .build())
                .collect(Collectors.toList());
        userSessionService.saveBatch(sessions);
        return;

//        // 2. 查出这些用户中真实存在的ID
//        List<Long> validUserIds = userService.listByIds(distinctIds)
//                .stream()
//                .map(User::getId)
//                .collect(Collectors.toList());
//
//        // 3. 创建会话（即使有效用户只剩一个人也允许，或者你可以再加判断）
//        ChatSession session = BeanUtil.copyProperties(dto, ChatSession.class);
//        session.setCreateTime(LocalDateTime.now());
//        session.setOwnerId(userId);
//        super.save(session);
//
//        // 4. 只为存在的用户建立会话关联
//        List<ChatUserSession> sessions = validUserIds.stream()
//                .map(id -> ChatUserSession.builder()
//                        .sessionId(session.getId())
//                        .userId(id)
//                        .joinTime(LocalDateTime.now())
//                        .lastReadTime(LocalDateTime.now())
//                        .build())
//                .collect(Collectors.toList());
//
//        userSessionService.saveBatch(sessions);
    }

    @Override
    public void deleteSession(Long sessionId) {
        ChatSession session = super.getById(sessionId);
        if (session == null) {
            throw new RuntimeException("会话不存在");
        }
        if (session.getOwnerId().equals(2L)) {
            super.removeById(sessionId);
        }
    }

    @Override
    public List<SessionVO> listSession() {
        Long userId = 2L;

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

        // 4. 组装 VO
        return userSessions.stream()
                .map(us -> {
                    ChatSession session = sessionMap.get(us.getSessionId());
                    if (session == null) return null;
                    return BeanUtil.copyProperties(session, SessionVO.class);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

}
