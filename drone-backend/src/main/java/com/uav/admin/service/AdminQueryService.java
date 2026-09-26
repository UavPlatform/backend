package com.uav.admin.service;

import com.uav.admin.pojo.vo.AdminOrderVo;
import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.admin.pojo.vo.AdminTaskVo;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.exception.BusinessException;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 1B-5 管理端业务查询（裁决 Q6/Q7=A，只读）。
 *
 * <p>全平台任务/订单分页列表与详情；分页参数化打样（page 从 0 起，size 上限 100）。
 * 查询经 EntityManager 动态 JPQL 实现（支持状态/编号过滤），不侵入 task/order 模块的仓库定义。
 */
@Slf4j
@Service
public class AdminQueryService {

    private static final int MAX_SIZE = 100;
    private static final int DEFAULT_SIZE = 20;

    @PersistenceContext
    private EntityManager em;

    private final UserRepository userRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;

    public AdminQueryService(UserRepository userRepository, TaskAssignmentRepository taskAssignmentRepository) {
        this.userRepository = userRepository;
        this.taskAssignmentRepository = taskAssignmentRepository;
    }

    // ---------- 订单 ----------

    @Transactional(readOnly = true)
    public AdminPageVo<AdminOrderVo> listOrders(int page, int size, String status, String orderNum, String taskNum) {
        int[] ps = sanitize(page, size);
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder();
        OrderStatus st = resolveOrderStatus(status);
        if (st != null) {
            where.append(" AND o.orderStatus = :status");
            params.put("status", st);
        }
        if (notBlank(orderNum)) {
            where.append(" AND o.orderNum = :orderNum");
            params.put("orderNum", orderNum.trim());
        }
        if (notBlank(taskNum)) {
            where.append(" AND o.task.taskNum = :taskNum");
            params.put("taskNum", taskNum.trim());
        }
        String whereSql = where.isEmpty() ? "" : "WHERE " + where.substring(5);

        List<com.uav.order.pojo.entity.MissionOrder> orders = bindParams(
                em.createQuery("SELECT o FROM MissionOrder o " + whereSql + " ORDER BY o.createTime DESC",
                        com.uav.order.pojo.entity.MissionOrder.class), params)
                .setFirstResult(ps[0] * ps[1])
                .setMaxResults(ps[1])
                .getResultList();
        long total = bindParams(
                em.createQuery("SELECT COUNT(o) FROM MissionOrder o " + whereSql, Long.class), params)
                .getSingleResult();

        List<AdminOrderVo> content = orders.stream()
                .map(o -> AdminOrderVo.of(o, resolveOwnerName(o.getUserId()),
                        o.getTask() != null ? o.getTask().getTaskName() : null))
                .collect(Collectors.toList());
        return AdminPageVo.of(content, ps[0], ps[1], total);
    }

    @Transactional(readOnly = true)
    public AdminOrderVo getOrderDetail(String orderNum) {
        com.uav.order.pojo.entity.MissionOrder order = em
                .createQuery("SELECT o FROM MissionOrder o WHERE o.orderNum = :orderNum",
                        com.uav.order.pojo.entity.MissionOrder.class)
                .setParameter("orderNum", orderNum)
                .getResultStream().findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));
        return AdminOrderVo.of(order, resolveOwnerName(order.getUserId()),
                order.getTask() != null ? order.getTask().getTaskName() : null);
    }

    // ---------- 任务 ----------

    @Transactional(readOnly = true)
    public AdminPageVo<AdminTaskVo> listTasks(int page, int size, String status, String taskNum) {
        int[] ps = sanitize(page, size);
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder();
        TaskStatus ts = resolveTaskStatus(status);
        if (ts != null) {
            where.append(" AND t.taskStatus = :taskStatus");
            params.put("taskStatus", ts);
        }
        if (notBlank(taskNum)) {
            where.append(" AND t.taskNum = :taskNum");
            params.put("taskNum", taskNum.trim());
        }
        String whereSql = where.isEmpty() ? "" : "WHERE " + where.substring(5);

        List<com.uav.task.pojo.entity.Task> tasks = bindParams(
                em.createQuery("SELECT t FROM Task t " + whereSql + " ORDER BY t.createTime DESC",
                        com.uav.task.pojo.entity.Task.class), params)
                .setFirstResult(ps[0] * ps[1])
                .setMaxResults(ps[1])
                .getResultList();
        long total = bindParams(
                em.createQuery("SELECT COUNT(t) FROM Task t " + whereSql, Long.class), params)
                .getSingleResult();

        List<AdminTaskVo> content = tasks.stream().map(this::toTaskVo).collect(Collectors.toList());
        return AdminPageVo.of(content, ps[0], ps[1], total);
    }

    @Transactional(readOnly = true)
    public AdminTaskVo getTaskDetail(String taskNum) {
        com.uav.task.pojo.entity.Task task = em
                .createQuery("SELECT t FROM Task t WHERE t.taskNum = :taskNum",
                        com.uav.task.pojo.entity.Task.class)
                .setParameter("taskNum", taskNum)
                .getResultStream().findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));
        return toTaskVo(task);
    }

    // ---------- 内部 ----------

    private AdminTaskVo toTaskVo(com.uav.task.pojo.entity.Task task) {
        String ownerName = resolveOwnerName(task.getUserId());
        com.uav.order.pojo.entity.MissionOrder order = em
                .createQuery("SELECT o FROM MissionOrder o WHERE o.task.id = :taskId",
                        com.uav.order.pojo.entity.MissionOrder.class)
                .setParameter("taskId", task.getId())
                .getResultStream().findFirst().orElse(null);

        String riderName = null;
        String completeNote = null;
        if (order != null) {
            // 接单记录经订单 taskId 关联（任务与订单 1:1）
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

        return AdminTaskVo.of(task, ownerName,
                order != null ? order.getOrderNum() : null,
                order != null ? order.getOrderStatus().getCode() : null,
                order != null ? order.getOrderStatus().name() : null,
                order != null ? order.getOrderStatus().getDesc() : null,
                order != null ? order.getTotalAmount() : null,
                order != null ? order.getTotalDistance() : null,
                riderName, completeNote, actionHint);
    }

    private String resolveOwnerName(Long userId) {
        return userRepository.findById(userId).map(User::getUserName).orElse(null);
    }

    private OrderStatus resolveOrderStatus(String status) {
        if (!notBlank(status)) {
            return null;
        }
        String s = status.trim();
        try {
            return OrderStatus.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ignore) {
            try {
                return OrderStatus.fromCode(Integer.parseInt(s));
            } catch (Exception ignore2) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                        "非法订单状态: " + status);
            }
        }
    }

    private TaskStatus resolveTaskStatus(String status) {
        if (!notBlank(status)) {
            return null;
        }
        String s = status.trim().toUpperCase();
        try {
            return TaskStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "非法任务状态: " + status);
        }
    }

    private int[] sanitize(int page, int size) {
        int p = Math.max(page, 0);
        int s = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new int[]{p, s};
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private <Q extends jakarta.persistence.Query> Q bindParams(Q query, Map<String, Object> params) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            query.setParameter(entry.getKey(), entry.getValue());
        }
        return query;
    }
}
