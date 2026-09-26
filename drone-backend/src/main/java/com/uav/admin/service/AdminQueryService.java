package com.uav.admin.service;

import com.uav.admin.pojo.vo.AdminOrderVo;
import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.admin.pojo.vo.AdminPilotDroneVo;
import com.uav.admin.pojo.vo.AdminPilotDetailVo;
import com.uav.admin.pojo.vo.AdminPilotVo;
import com.uav.admin.pojo.vo.AdminTaskVo;
import com.uav.admin.pojo.vo.AdminUserDetailVo;
import com.uav.admin.pojo.vo.AdminUserVo;
import com.uav.aircraft.pojo.entity.AircraftModel;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 1B-5 管理端业务查询（裁决 Q6/Q7=A，只读）。
 *
 * <p>全平台任务/订单分页列表与详情；分页参数化打样（page 从 0 起，size 上限 100）。
 * <p>查询经 EntityManager 动态 JPQL 实现（支持状态/编号过滤），不侵入 task/order 模块的仓库定义。
 *
 * <p>TASK-BACKEND-006 增补监管端主体查询：注册用户（role=0）/ 注册飞手（role=1）列表与详情，
 * 飞手详情含绑定无人机表（djiId/机型/在线/可用）与关联订单；无人机启停复用
 * {@code POST /admin/uav/available}（按 djiId），不另设端点。
 */
@Slf4j
@Service
public class AdminQueryService {

    private static final int MAX_SIZE = 100;
    private static final int DEFAULT_SIZE = 20;

    /** user.role：0 普通用户（发单方）；1 飞手（执飞方）。 */
    private static final int ROLE_USER = 0;
    private static final int ROLE_PILOT = 1;

    /** uav.online_status / is_available 的「是」值。 */
    private static final char FLAG_ON = '1';

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

    // ---------- 注册主体（TASK-BACKEND-006 / REQ-FRONTEND-001 / ADR-0004）----------

    /**
     * 注册用户分页列表（role=0；飞手见 {@link #listPilots}）。
     * 支持用户名关键字（LIKE，忽略大小写）与账号状态精确筛选；订单数按页内 userId 批量聚合，避免 N+1。
     */
    @Transactional(readOnly = true)
    public AdminPageVo<AdminUserVo> listUsers(int page, int size, String keyword, Integer status) {
        int[] ps = sanitize(page, size);
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder(" WHERE u.role = :role");
        params.put("role", ROLE_USER);
        appendKeywordLike(where, params, keyword);
        if (status != null) {
            where.append(" AND u.status = :status");
            params.put("status", status);
        }
        String whereSql = where.toString();

        List<User> users = bindParams(
                em.createQuery("SELECT u FROM User u" + whereSql + " ORDER BY u.id DESC", User.class), params)
                .setFirstResult(ps[0] * ps[1])
                .setMaxResults(ps[1])
                .getResultList();
        long total = bindParams(em.createQuery("SELECT COUNT(u) FROM User u" + whereSql, Long.class), params)
                .getSingleResult();

        Map<Long, Long> orderCounts = groupCount(
                "SELECT o.userId, COUNT(o.id) FROM MissionOrder o WHERE o.userId IN :ids GROUP BY o.userId",
                userIds(users), Map.of());
        List<AdminUserVo> content = users.stream()
                .map(u -> new AdminUserVo(u.getId(), u.getUserName(), u.getStatus(),
                        orderCounts.getOrDefault(u.getId(), 0L)))
                .collect(Collectors.toList());
        return AdminPageVo.of(content, ps[0], ps[1], total);
    }

    /**
     * 用户详情 + 关联订单摘要（orderNum、任务、状态、金额，复用 {@link AdminOrderVo}）。
     * 只认非飞手主体：飞手 ID 走 {@link #getPilotDetail}，其余不存在的 ID → 404 USER_NOT_FOUND。
     */
    @Transactional(readOnly = true)
    public AdminUserDetailVo getUserDetail(Long userId) {
        User user = requireSubject(userId, false);
        List<com.uav.order.pojo.entity.MissionOrder> orders = em.createQuery(
                "SELECT o FROM MissionOrder o WHERE o.userId = :userId ORDER BY o.createTime DESC",
                com.uav.order.pojo.entity.MissionOrder.class)
                .setParameter("userId", userId)
                .getResultList();
        return new AdminUserDetailVo(user.getId(), user.getUserName(), user.getStatus(), user.getRole(),
                toOrderVos(orders));
    }

    /**
     * 注册飞手分页列表（role=1）：支持用户名关键字筛选；绑定数/在线数/完成单按页内 ID 批量聚合。
     */
    @Transactional(readOnly = true)
    public AdminPageVo<AdminPilotVo> listPilots(int page, int size, String keyword) {
        int[] ps = sanitize(page, size);
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder(" WHERE u.role = :role");
        params.put("role", ROLE_PILOT);
        appendKeywordLike(where, params, keyword);
        String whereSql = where.toString();

        List<User> pilots = bindParams(
                em.createQuery("SELECT u FROM User u" + whereSql + " ORDER BY u.id DESC", User.class), params)
                .setFirstResult(ps[0] * ps[1])
                .setMaxResults(ps[1])
                .getResultList();
        long total = bindParams(em.createQuery("SELECT COUNT(u) FROM User u" + whereSql, Long.class), params)
                .getSingleResult();

        List<Long> ids = userIds(pilots);
        Map<Long, Long> uavCounts = groupCount(
                "SELECT r.userId, COUNT(r.id) FROM RiderUav r WHERE r.userId IN :ids GROUP BY r.userId",
                ids, Map.of());
        Map<Long, Long> onlineUavCounts = groupCount(
                "SELECT r.userId, COUNT(r.id) FROM RiderUav r, Uav u WHERE u.djiId = r.djiId "
                        + "AND u.onlineStatus = :flag AND r.userId IN :ids GROUP BY r.userId",
                ids, Map.of("flag", FLAG_ON));
        Map<Long, Long> completedCounts = groupCount(
                "SELECT a.riderId, COUNT(a.id) FROM TaskAssignment a "
                        + "WHERE a.riderId IN :ids AND a.completeTime IS NOT NULL GROUP BY a.riderId",
                ids, Map.of());

        List<AdminPilotVo> content = pilots.stream()
                .map(u -> new AdminPilotVo(u.getId(), u.getUserName(), u.getStatus(),
                        uavCounts.getOrDefault(u.getId(), 0L).intValue(),
                        onlineUavCounts.getOrDefault(u.getId(), 0L).intValue(),
                        completedCounts.getOrDefault(u.getId(), 0L)))
                .collect(Collectors.toList());
        return AdminPageVo.of(content, ps[0], ps[1], total);
    }

    /**
     * 飞手详情：基本信息 + 绑定无人机表（djiId、机型名、在线、可用）+ 关联订单。
     *
     * <p>关联订单口径：已有接单记录（task_assignment.rider_id）或已被用户选定
     * （mission_order.selected_application_id → 应征.rider_id）的订单；纯应征未选定不计入。
     */
    @Transactional(readOnly = true)
    public AdminPilotDetailVo getPilotDetail(Long userId) {
        User pilot = requireSubject(userId, true);

        List<RiderUav> bindings = em.createQuery(
                "SELECT r FROM RiderUav r WHERE r.userId = :userId ORDER BY r.createTime ASC, r.id ASC",
                RiderUav.class)
                .setParameter("userId", userId)
                .getResultList();

        List<com.uav.order.pojo.entity.MissionOrder> orders = em.createQuery(
                "SELECT o FROM MissionOrder o WHERE o.task.id IN "
                        + "(SELECT a.taskId FROM TaskAssignment a WHERE a.riderId = :riderId) "
                        + "OR o.selectedApplicationId IN "
                        + "(SELECT app.id FROM TaskApplication app WHERE app.riderId = :riderId) "
                        + "ORDER BY o.createTime DESC",
                com.uav.order.pojo.entity.MissionOrder.class)
                .setParameter("riderId", userId)
                .getResultList();

        long completed = em.createQuery(
                "SELECT COUNT(a) FROM TaskAssignment a WHERE a.riderId = :riderId AND a.completeTime IS NOT NULL",
                Long.class)
                .setParameter("riderId", userId)
                .getSingleResult();

        return new AdminPilotDetailVo(pilot.getId(), pilot.getUserName(), pilot.getStatus(), pilot.getRole(),
                completed, toDroneVos(bindings), toOrderVos(orders));
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

    /** 用户名关键字 → {@code AND LOWER(u.userName) LIKE :keyword}（同时小写入参，H2/MySQL 一致）。 */
    private void appendKeywordLike(StringBuilder where, Map<String, Object> params, String keyword) {
        if (notBlank(keyword)) {
            where.append(" AND LOWER(u.userName) LIKE :keyword");
            params.put("keyword", "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%");
        }
    }

    private List<Long> userIds(List<User> users) {
        return users.stream().map(User::getId).collect(Collectors.toList());
    }

    /**
     * 分组计数查询（ids 为空直接短路，不发空 IN 查询）：返回 {@code [分组键 → 计数]}。
     * {@code extraParams} 承载如 {@code uav.online_status = :flag} 之类的附加绑定参数。
     */
    private Map<Long, Long> groupCount(String jpql, List<Long> ids, Map<String, Object> extraParams) {
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

    /**
     * 按 ID 取主体并校验实体类别：{@code pilot=true} 要求 role=1（飞手），否则要求 role≠1
     * （用户/管理员——管理员可建任务，订单属主可能是 role=2，详情需可回链）。不符或不存在 → 404。
     */
    private User requireSubject(Long userId, boolean pilot) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> notFound(userId, pilot));
        boolean isPilot = user.getRole() != null && user.getRole() == ROLE_PILOT;
        if (isPilot != pilot) {
            throw notFound(userId, pilot);
        }
        return user;
    }

    private BusinessException notFound(Long userId, boolean pilot) {
        return new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.USER_NOT_FOUND,
                pilot ? "飞手不存在: " + userId : "用户不存在: " + userId);
    }

    /** 绑定记录 → 无人机行：机型目录与设备档案各批量查一次，缺失语义见 VO 文档。 */
    private List<AdminPilotDroneVo> toDroneVos(List<RiderUav> bindings) {
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

    /** 订单 → 摘要 VO 列表（属主名与任务名逐条补齐，与订单列表同构）。 */
    private List<AdminOrderVo> toOrderVos(List<com.uav.order.pojo.entity.MissionOrder> orders) {
        return orders.stream()
                .map(o -> AdminOrderVo.of(o, resolveOwnerName(o.getUserId()),
                        o.getTask() != null ? o.getTask().getTaskName() : null))
                .collect(Collectors.toList());
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
