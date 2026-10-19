package com.uav.admin.service;

import com.uav.admin.pojo.vo.AdminOrderVo;
import com.uav.admin.pojo.vo.AdminPilotDroneVo;
import com.uav.admin.pojo.vo.AdminTaskVo;
import com.uav.admin.pojo.vo.AdminUserComplaintVo;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.live.service.impl.LiveDeviceResolver;
import com.uav.order.pojo.entity.OrderComplaint;
import com.uav.pay.mapper.PayRecordRepository;
import com.uav.pay.pojo.entity.PayRecord;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.exception.BusinessException;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.uav.pojo.entity.Uav;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.RiderUav;
import com.uav.user.pojo.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared query plumbing for the admin read models: pagination, parameter binding,
 * subject validation, VO mapping and the paid-at/device resolution rules.
 */
@Component
public class AdminQuerySupport {

    static final int MAX_SIZE = 100;
    static final int DEFAULT_SIZE = 20;
    static final int ROLE_USER = 0;
    static final int ROLE_PILOT = 1;
    static final char FLAG_ON = '1';

    private static final Set<OrderStatus> PAID_STATES =
            Set.of(OrderStatus.PAID, OrderStatus.WAITING_CONFIRM, OrderStatus.COMPLETED,
                    OrderStatus.REFUNDED, OrderStatus.DISPUTED);

    @PersistenceContext
    private EntityManager em;

    private final UserRepository userRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final LiveDeviceResolver liveDeviceResolver;
    private final PayRecordRepository payRecordRepository;

    public AdminQuerySupport(UserRepository userRepository,
                             TaskAssignmentRepository taskAssignmentRepository,
                             LiveDeviceResolver liveDeviceResolver,
                             PayRecordRepository payRecordRepository) {
        this.userRepository = userRepository;
        this.taskAssignmentRepository = taskAssignmentRepository;
        this.liveDeviceResolver = liveDeviceResolver;
        this.payRecordRepository = payRecordRepository;
    }

    public EntityManager em() {
        return em;
    }

    public AdminTaskVo toTaskVo(com.uav.task.pojo.entity.Task task) {
        String ownerName = resolveOwnerName(task.getUserId());
        com.uav.order.pojo.entity.MissionOrder order = em
                .createQuery("SELECT o FROM MissionOrder o WHERE o.task.id = :taskId",
                        com.uav.order.pojo.entity.MissionOrder.class)
                .setParameter("taskId", task.getId())
                .getResultStream().findFirst().orElse(null);

        String riderName = null;
        String completeNote = null;
        if (order != null) {
            var assignment = taskAssignmentRepository.findByTaskId(task.getId()).orElse(null);
            if (assignment != null) {
                riderName = userRepository.findById(assignment.getRiderId())
                        .map(User::getUserName).orElse(null);
                completeNote = assignment.getCompleteNote();
            }
        }
        String actionHint = com.uav.task.pojo.vo.TaskActionHints.hint(task.getTaskStatus(),
                order != null ? order.getOrderStatus() : null,
                task.getMatchStatus());

        Map<String, PayRecord> payRecords = payRecordsByOrderNum(
                order != null ? List.of(order.getOrderNum()) : List.of());
        return AdminTaskVo.of(task, ownerName, order,
                resolveDeviceId(task.getId()), riderName, completeNote, actionHint,
                paidAt(order, payRecords));
    }

    public void appendKeywordLike(StringBuilder where, Map<String, Object> params, String keyword) {
        if (notBlank(keyword)) {
            where.append(" AND LOWER(u.userName) LIKE :keyword");
            params.put("keyword", "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%");
        }
    }

    public List<Long> userIds(List<User> users) {
        return users.stream().map(User::getId).collect(Collectors.toList());
    }

    public Map<Long, Long> groupCount(String jpql, List<Long> ids, Map<String, Object> extraParams) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var query = em.createQuery(jpql, Object[].class).setParameter("ids", ids);
        for (Map.Entry<String, Object> entry : extraParams.entrySet()) {
            query.setParameter(entry.getKey(), entry.getValue());
        }
        return query.getResultList().stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    public User requireSubject(Long userId, boolean pilot) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> notFound(userId, pilot));
        boolean isPilot = user.getRole() != null && user.getRole() == ROLE_PILOT;
        if (isPilot != pilot) {
            throw notFound(userId, pilot);
        }
        return user;
    }

    public BusinessException notFound(Long userId, boolean pilot) {
        return new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.USER_NOT_FOUND,
                pilot ? "飞手不存在: " + userId : "用户不存在: " + userId);
    }

    public List<AdminPilotDroneVo> toDroneVos(List<RiderUav> bindings) {
        if (bindings.isEmpty()) {
            return List.of();
        }
        Set<Long> modelIds = bindings.stream()
                .map(RiderUav::getAircraftModelId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, AircraftModel> models = modelIds.isEmpty() ? Map.of()
                : em.createQuery("SELECT m FROM AircraftModel m WHERE m.id IN :ids", AircraftModel.class)
                        .setParameter("ids", modelIds)
                        .getResultList().stream()
                        .collect(Collectors.toMap(AircraftModel::getId, m -> m));
        Map<String, Uav> uavs = em.createQuery("SELECT u FROM Uav u WHERE u.djiId IN :djiIds", Uav.class)
                .setParameter("djiIds", bindings.stream().map(RiderUav::getDjiId).collect(Collectors.toList()))
                .getResultList().stream()
                .collect(Collectors.toMap(Uav::getDjiId, u -> u, (a, b) -> a));

        return bindings.stream().map(binding -> {
            Uav uav = uavs.get(binding.getDjiId());
            AircraftModel model = binding.getAircraftModelId() == null
                    ? null : models.get(binding.getAircraftModelId());
            return new AdminPilotDroneVo(
                    binding.getDjiId(),
                    binding.getAircraftModelId(),
                    model != null ? model.getDisplayName() : null,
                    uav != null && uav.getOnlineStatus() != null && uav.getOnlineStatus() == FLAG_ON,
                    uav == null ? null
                            : uav.getIsAvailable() != null && uav.getIsAvailable() == FLAG_ON);
        }).collect(Collectors.toList());
    }

    public List<AdminUserComplaintVo> toComplaintVos(List<OrderComplaint> complaints) {
        return complaints.stream()
                .map(c -> new AdminUserComplaintVo(
                        c.getId(),
                        c.getOrderNum(),
                        c.getReason() != null ? c.getReason().name() : null,
                        c.getStatus() != null ? c.getStatus().name() : null,
                        c.getDescription(),
                        c.getCreateTime(),
                        c.getResolveTime(),
                        c.getAdminNote()))
                .collect(Collectors.toList());
    }

    public List<AdminOrderVo> toOrderVos(List<com.uav.order.pojo.entity.MissionOrder> orders) {
        Map<String, PayRecord> payRecords = payRecordsByOrderNum(orderNumsOf(orders));
        return orders.stream()
                .map(o -> toOrderVo(o, payRecords))
                .collect(Collectors.toList());
    }

    public AdminOrderVo toOrderVo(com.uav.order.pojo.entity.MissionOrder order,
                                  Map<String, PayRecord> payRecords) {
        return AdminOrderVo.of(order, resolveOwnerName(order.getUserId()),
                order.getTask() != null ? order.getTask().getTaskName() : null,
                resolveDeviceId(order.getTask() != null ? order.getTask().getId() : null),
                paidAt(order, payRecords));
    }

    public List<String> orderNumsOf(List<com.uav.order.pojo.entity.MissionOrder> orders) {
        return orders.stream()
                .map(com.uav.order.pojo.entity.MissionOrder::getOrderNum)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Map<String, PayRecord> payRecordsByOrderNum(List<String> orderNums) {
        if (orderNums.isEmpty()) {
            return Map.of();
        }
        return payRecordRepository.findByOrderNumIn(orderNums).stream()
                .collect(Collectors.toMap(PayRecord::getOrderNum, r -> r, (a, b) -> a));
    }

    public LocalDateTime paidAt(com.uav.order.pojo.entity.MissionOrder order,
                                Map<String, PayRecord> payRecords) {
        if (order == null) {
            return null;
        }
        PayRecord record = payRecords.get(order.getOrderNum());
        if (record != null && record.getPayTime() != null) {
            return record.getPayTime();
        }
        if (PAID_STATES.contains(order.getOrderStatus())) {
            return order.getUpdateTime();
        }
        return null;
    }

    public String resolveDeviceId(Long taskId) {
        if (taskId == null) {
            return null;
        }
        return liveDeviceResolver.resolveForTask(taskId).deviceId();
    }

    public String resolveOwnerName(Long userId) {
        return userRepository.findById(userId).map(User::getUserName).orElse(null);
    }

    public OrderStatus resolveOrderStatus(String status) {
        return OrderStatus.fromNameOrCode(status);
    }

    public TaskStatus resolveTaskStatus(String status) {
        if (!notBlank(status)) {
            return null;
        }
        String s = status.trim().toUpperCase();
        try {
            return TaskStatus.valueOf(s);
        } catch (IllegalArgumentException error) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "非法任务状态: " + status);
        }
    }

    public int[] sanitize(int page, int size) {
        int p = Math.max(page, 0);
        int s = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new int[]{p, s};
    }

    public boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public <Q extends jakarta.persistence.Query> Q bindParams(Q query, Map<String, Object> params) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            query.setParameter(entry.getKey(), entry.getValue());
        }
        return query;
    }
}
