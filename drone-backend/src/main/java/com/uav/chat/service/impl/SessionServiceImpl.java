package com.uav.chat.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.uav.chat.mapper.ChatSessionMapper;
import com.uav.chat.pojo.dto.SessionDTO;
import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.pojo.vo.SessionVO;
import com.uav.chat.service.SessionService;
import com.uav.chat.service.UserSessionService;
import com.uav.server.util.UserContext;
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

//    @Transactional(rollbackFor = Exception.class)
    @Override
    public void createSession(SessionDTO dto) {
        Long userId = UserContext.getUserId();
        List<Long> ids = dto.getUserIds();

        // 1. 包含创建者并去重
        ids.add(userId);
        List<Long> distinctIds = ids.stream().distinct().toList();

        // 先用这个测试
        ChatSession session = BeanUtil.copyProperties(dto, ChatSession.class);
        long currentTimeMillis = System.currentTimeMillis();
        session.setCreateTime(currentTimeMillis);
        session.setOwnerId(userId);
        super.save(session);
        List<ChatUserSession> sessions = distinctIds.stream()
                .map(id -> ChatUserSession.builder()
                        .sessionId(session.getId())
                        .userId(id)
                        .joinTime(currentTimeMillis)
                        .lastReadTime(currentTimeMillis)
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
//        // 3. 创建会话（即使有效用户只剩一个人也允许或者可以再加判断）
//        ChatSession session = BeanUtil.copyProperties(dto, ChatSession.class);
//        session.setCreateTime(LocalDateTime.now());
//        session.setOwnerId(userId);
//        if (session.getName() == null) {
//            String sessionName = session.getUserIds().stream()
//                    .map(id -> userService.getById(id).getName())
//                    .collect(Collectors.joining("、"));
//            session.setName(sessionName);
//        }
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
        if (session.getOwnerId().equals(UserContext.getUserId())) {
            super.removeById(sessionId);
        }
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

    @Override
    public List<Long> getUserIdsBySessionId(Long sessionId) {
        return super.getById(sessionId).getUserIds();
    }

}
