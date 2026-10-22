package com.uav.task.service.impl;

import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.ApplicationStatus;
import com.uav.server.enums.MatchStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.exception.BusinessException;
import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskApplication;
import com.uav.task.pojo.entity.TaskAssignment;
import com.uav.task.pojo.vo.RiderStatsVO;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-side queries for tasks, rider work lists and rider statistics.
 */
@Service
@Slf4j
public class TaskQueryService {

    private final TaskRepository taskRepository;
    private final TaskApplicationRepository taskApplicationRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final UserRepository userRepository;

    public TaskQueryService(TaskRepository taskRepository,
                            TaskApplicationRepository taskApplicationRepository,
                            TaskAssignmentRepository taskAssignmentRepository,
                            UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.taskApplicationRepository = taskApplicationRepository;
        this.taskAssignmentRepository = taskAssignmentRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Page<Task> getTasksByUser(Long userId, int page, int size) {
        return taskRepository.findByUserIdOrderByCreateTimeDesc(userId, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Task getTaskByTaskNum(String taskNum, Long userId) {
        Task task = taskRepository.findByTaskNum(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ROUTE_NOT_FOUND, "无权查看此任务");
        }
        return task;
    }

    @Transactional(readOnly = true)
    public Task getTaskByTaskNum(String taskNum) {
        return taskRepository.findByTaskNum(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<Task> getAvailableTasks() {
        // ADR-0003 闲鱼式撮合：大厅 = 待撮合的空闲任务（招募中/洽谈中），不再要求订单已支付
        return taskRepository.findMatchingTasks(TaskStatus.IDLE,
                List.of(MatchStatus.SEEKING_RIDER, MatchStatus.NEGOTIATING));
    }

    @Transactional(readOnly = true)
    public List<Task> getRiderActiveTasks(Long riderId) {
        List<TaskAssignment> assignments = taskAssignmentRepository
                .findByRiderIdAndCompleteTimeIsNullOrderByAcceptTimeDescIdDesc(riderId);
        List<Long> assignedTaskIds = assignments.stream()
                .map(TaskAssignment::getTaskId)
                .toList();
        Set<Long> assignedIdSet = new HashSet<>(assignedTaskIds);

        // Confirmation creates task_assignment only at the rider-confirm step; earlier
        // matching stages exist only as applications and must still appear here.
        List<Long> applicationTaskIds = taskApplicationRepository
                .findByRiderIdAndStatusIn(riderId, List.of(ApplicationStatus.ACTIVE, ApplicationStatus.SELECTED))
                .stream()
                .map(TaskApplication::getTaskId)
                .filter(id -> !assignedIdSet.contains(id))
                .distinct()
                .toList();
        List<Long> allIds = new ArrayList<>(applicationTaskIds);
        allIds.addAll(assignedTaskIds);
        List<Task> all = findAllInOrder(allIds);
        Set<Long> preConfirmIds = new HashSet<>(applicationTaskIds);
        List<Task> preConfirm = all.stream()
                .filter(task -> preConfirmIds.contains(task.getId()))
                .filter(task -> task.getTaskStatus() == TaskStatus.IDLE)
                .filter(task -> task.getMatchStatus() != MatchStatus.CLOSED)
                .sorted(Comparator.comparing(Task::getUpdateTime,
                                Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder()))
                        .thenComparing(Task::getId, Comparator.reverseOrder()))
                .toList();

        List<Task> result = new ArrayList<>(preConfirm);
        all.stream().filter(task -> !preConfirmIds.contains(task.getId())).forEach(result::add);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Task> getRiderAllTasks(Long riderId) {
        List<TaskAssignment> assignments = taskAssignmentRepository.findByRiderIdOrderByAcceptTimeDescIdDesc(riderId);
        List<Long> taskIds = assignments.stream()
                .map(TaskAssignment::getTaskId)
                .toList();
        return findAllInOrder(taskIds);
    }

    /** {@code findAllById} does not preserve order: reorder by assignment (newest first). */
    private List<Task> findAllInOrder(List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Task> byId = new HashMap<>();
        for (Task task : taskRepository.findAllById(taskIds)) {
            byId.put(task.getId(), task);
        }
        return taskIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    @Transactional(readOnly = true)
    public RiderStatsVO getRiderStats(Long riderId) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        RiderStatsVO vo = new RiderStatsVO();
        vo.setTodayOrders(taskAssignmentRepository.countByRiderIdAndAcceptTimeAfter(riderId, todayStart));
        vo.setTotalCompleted(taskAssignmentRepository.countByRiderIdAndCompleteTimeIsNotNull(riderId));
        vo.setTotalEarnings(taskAssignmentRepository.sumRewardByRiderId(riderId));
        return vo;
    }

    @Transactional(readOnly = true)
    public List<RiderStatsVO> getRecommendedRiders() {
        List<User> riders = userRepository.findByRole(1);
        if (riders.isEmpty()) {
            return List.of();
        }

        List<Long> riderIds = riders.stream().map(User::getId).toList();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        Map<Long, Object[]> statsByRider = new HashMap<>();
        for (Object[] row : taskAssignmentRepository.batchRiderStats(riderIds, todayStart)) {
            statsByRider.put(((Number) row[0]).longValue(), row);
        }

        List<RiderStatsVO> result = new ArrayList<>();
        for (User rider : riders) {
            RiderStatsVO vo = new RiderStatsVO();
            vo.setRiderId(rider.getId());
            vo.setRiderName(rider.getUserName());
            Object[] stats = statsByRider.get(rider.getId());
            vo.setTodayOrders(stats != null ? ((Number) stats[1]).longValue() : 0L);
            vo.setTotalCompleted(stats != null ? ((Number) stats[2]).longValue() : 0L);
            vo.setTotalEarnings(stats != null ? ((Number) stats[3]).doubleValue() : 0.0);
            result.add(vo);
        }
        result.sort(Comparator.comparingLong(RiderStatsVO::getTotalCompleted).reversed());
        return result;
    }
}
