package com.uav.chat.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import com.uav.chat.pojo.dto.SessionDTO;
import com.uav.chat.pojo.entity.ChatMessage;
import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.pojo.vo.SessionVO;
import com.uav.chat.pojo.vo.TaskChatSessionVO;
import com.uav.chat.repository.ChatMessageRepository;
import com.uav.chat.repository.ChatSessionRepository;
import com.uav.chat.service.SessionService;
import com.uav.chat.service.UserSessionService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.UserContext;
import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskApplication;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
    private final TaskRepository taskRepository;
    private final TaskApplicationRepository applicationRepository;

    public SessionServiceImpl(ChatSessionRepository chatSessionRepository,
                              UserSessionService userSessionService,
                              UserRepository userRepository,
                              ChatMessageRepository chatMessageRepository,
                              TaskRepository taskRepository,
                              TaskApplicationRepository applicationRepository) {
        this.chatSessionRepository = chatSessionRepository;
        this.userSessionService = userSessionService;
        this.userRepository = userRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.taskRepository = taskRepository;
        this.applicationRepository = applicationRepository;
    }

    @Override
    @Transactional
    public SessionVO createSession(SessionDTO dto) {
        Long userId = UserContext.getUserId();
        // 任务会话（ADR-0003：会话绑定 taskNum 用于订单内洽谈）走独立链路：
        // 参与方限定「任务属主 ↔ 应征飞手」、按 task+rider 去重、落 taskNum/applicationId
        if (dto.getTaskNum() != null && !dto.getTaskNum().isBlank()) {
            return createTaskSession(dto, userId);
        }
        List<Long> ids = new ArrayList<>(dto.getUserIds());
        ids.add(userId);
        List<Long> distinctIds = ids.stream().distinct().toList();

        if (dto.getType() != null && dto.getType() == 0 && distinctIds.size() == 2) {
            List<SessionVO> existing = listSession();
            for (SessionVO s : existing) {
                // 任务会话不参与通用去重：任务绑定会话只由任务链路按 task+rider 复用
                if (s.getType() != null && s.getType() == 0 && s.getTaskNum() == null) {
                    List<Long> sIds = getUserIdsBySessionId(s.getId());
                    if (sIds != null && sIds.size() == 2
                            && sIds.containsAll(distinctIds)) {
                        return s;
                    }
                }
            }
        }

        ChatSession session = BeanUtil.copyProperties(dto, ChatSession.class);
        // 任务绑定字段仅任务会话链路写入；通用会话忽略客户端传入的 taskNum 空串 / applicationId
        session.setTaskNum(null);
        session.setApplicationId(null);
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

    /**
     * 创建任务会话（TASK-BACKEND-005 / ADR-0003 决定 1「会话绑定 taskNum 用于订单内洽谈」）。
     *
     * <p>规则：参与方固定为「任务属主 ↔ 该任务的应征飞手」（恰好 2 人）；调用方与对方都必须是
     * 任务属主或在该任务存在应征记录（TaskApplication 存在即算，含 ACTIVE/SELECTED）；
     * 同一任务 + 同一飞手重复创建复用既有会话；applicationId 落库关联（不传则按应征记录带上，
     * 传了但与实际应征记录不一致则拒绝）。
     *
     * <p>会话 {@code ownerId} 固定为任务属主——订单内洽谈会话的管理权归任务属主，飞手先发起也一样。
     */
    private SessionVO createTaskSession(SessionDTO dto, Long userId) {
        if (dto.getType() == null || dto.getType() != 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "任务会话为一对一会话（type=0）");
        }
        Task task = taskRepository.findByTaskNum(dto.getTaskNum())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));
        Long ownerId = task.getUserId();

        // 授权：仅任务属主与该任务的应征/选定飞手可创建
        if (!userId.equals(ownerId)
                && applicationRepository.findByTaskIdAndRiderId(task.getId(), userId).isEmpty()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION,
                    "仅任务属主与该任务的应征飞手可创建任务会话");
        }

        // 参与方必须恰好是 {任务属主, 一名飞手}（调用方并入后去重）
        List<Long> ids = new ArrayList<>(dto.getUserIds());
        ids.add(userId);
        List<Long> distinctIds = ids.stream().distinct().toList();
        if (distinctIds.size() != 2 || !distinctIds.contains(ownerId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "任务会话参与方必须是任务属主与一名应征飞手");
        }
        Long riderId = distinctIds.stream().filter(id -> !id.equals(ownerId)).findFirst().orElseThrow();

        // 对方飞手必须在该任务有应征记录
        TaskApplication application = applicationRepository
                .findByTaskIdAndRiderId(task.getId(), riderId)
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION,
                        "对方不是该任务的应征飞手"));
        if (dto.getApplicationId() != null && !dto.getApplicationId().equals(application.getId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "applicationId 与该飞手的应征记录不一致");
        }

        // 同一任务 + 同一飞手去重复用
        for (ChatSession existing : chatSessionRepository.findByTaskNum(task.getTaskNum())) {
            if (getUserIdsBySessionId(existing.getId()).contains(riderId)) {
                return toSessionVO(existing, userId);
            }
        }

        long now = System.currentTimeMillis();
        ChatSession session = ChatSession.builder()
                .name(dto.getName())
                .type(0)
                .userIds(distinctIds)
                .ownerId(ownerId)
                .avatar(dto.getAvatar())
                .description(dto.getDescription())
                .taskNum(task.getTaskNum())
                .applicationId(application.getId())
                .createTime(now)
                .build();
        chatSessionRepository.saveAndFlush(session);

        List<ChatUserSession> chatUserSessions = distinctIds.stream()
                .map(id -> ChatUserSession.builder()
                        .sessionId(session.getId())
                        .userId(id)
                        .joinTime(now)
                        .lastReadTime(now)
                        .build())
                .collect(Collectors.toList());
        userSessionService.saveAll(chatUserSessions);

        return toSessionVO(session, userId);
    }

    /** 单会话 → VO（成员、对方用户名、最后一条消息、当前用户未读数）；任务会话创建/去重复用共用。 */
    private SessionVO toSessionVO(ChatSession session, Long viewerId) {
        SessionVO vo = BeanUtil.copyProperties(session, SessionVO.class);
        List<Long> memberIds = getUserIdsBySessionId(session.getId());
        vo.setUserIds(memberIds);
        if (memberIds.size() == 2) {
            Long otherId = memberIds.stream().filter(id -> !id.equals(viewerId)).findFirst().orElse(null);
            if (otherId != null) {
                userRepository.findById(otherId).ifPresent(u -> vo.setOtherUserName(u.getUserName()));
            }
        }
        ChatMessage lastMsg = chatMessageRepository
                .findFirstBySessionIdOrderByCreateTimeDesc(session.getId())
                .orElse(null);
        if (lastMsg != null) {
            vo.setLastMessage(lastMsg.getContent());
            vo.setLastMessageTime(lastMsg.getCreateTime());
        }
        vo.setUnreadCount(unreadFor(session.getId(), viewerId));
        return vo;
    }

    /** 当前用户在该会话的未读数（无读取记录时为 0）。 */
    private int unreadFor(Long sessionId, Long userId) {
        Long since = userSessionService.findBySessionId(sessionId).stream()
                .filter(us -> us.getUserId().equals(userId))
                .findFirst()
                .map(us -> us.getLastReadTime() != null ? us.getLastReadTime() : us.getJoinTime())
                .orElse(null);
        return since != null ? chatMessageRepository.countUnread(sessionId, since, userId) : 0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskChatSessionVO> listTaskSessions(String taskNum, Long userId) {
        if (taskNum == null || taskNum.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "taskNum 不能为空");
        }
        Task task = taskRepository.findByTaskNum(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));
        boolean isOwner = userId.equals(task.getUserId());
        if (!isOwner && applicationRepository.findByTaskIdAndRiderId(task.getId(), userId).isEmpty()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION,
                    "仅任务属主与该任务的应征飞手可查看任务会话");
        }

        List<TaskChatSessionVO> result = new ArrayList<>();
        for (ChatSession session : chatSessionRepository.findByTaskNum(taskNum)) {
            List<Long> memberIds = getUserIdsBySessionId(session.getId());
            // 成员过滤：任务属主可见该任务全部会话，应征飞手仅见自己参与的会话
            if (!memberIds.contains(userId)) {
                continue;
            }
            TaskChatSessionVO vo = new TaskChatSessionVO();
            vo.setSessionId(session.getId());
            vo.setName(session.getName());
            vo.setType(session.getType());
            vo.setTaskNum(session.getTaskNum());
            vo.setApplicationId(session.getApplicationId());
            vo.setOwnerId(session.getOwnerId());

            Long riderId = memberIds.stream().filter(id -> !id.equals(task.getUserId())).findFirst().orElse(null);
            vo.setRiderId(riderId);
            if (riderId != null) {
                userRepository.findById(riderId).ifPresent(u -> vo.setRiderName(u.getUserName()));
            }
            Long otherId = memberIds.stream().filter(id -> !id.equals(userId)).findFirst().orElse(null);
            vo.setOtherUserId(otherId);
            if (otherId != null) {
                userRepository.findById(otherId).ifPresent(u -> vo.setOtherUserName(u.getUserName()));
            }
            vo.setCreateTime(session.getCreateTime());

            ChatMessage lastMsg = chatMessageRepository
                    .findFirstBySessionIdOrderByCreateTimeDesc(session.getId())
                    .orElse(null);
            if (lastMsg != null) {
                vo.setLastMessage(lastMsg.getContent());
                vo.setLastMessageTime(lastMsg.getCreateTime());
            }
            vo.setUnreadCount(unreadFor(session.getId(), userId));
            result.add(vo);
        }
        return result;
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
