package com.uav.admin.service;

import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.admin.pojo.vo.AdminPilotDetailVo;
import com.uav.admin.pojo.vo.AdminPilotVo;
import com.uav.admin.pojo.vo.AdminUserDetailVo;
import com.uav.admin.pojo.vo.AdminUserVo;
import com.uav.order.pojo.entity.OrderComplaint;
import com.uav.rider.pojo.vo.RiderInfoVO;
import com.uav.rider.service.RiderInfoService;
import com.uav.user.pojo.entity.RiderUav;
import com.uav.user.pojo.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Admin read models for registered users and pilots. */
@Service
public class AdminSubjectQueryService {

    private static final int ROLE_USER = AdminQuerySupport.ROLE_USER;
    private static final int ROLE_PILOT = AdminQuerySupport.ROLE_PILOT;
    private static final char FLAG_ON = AdminQuerySupport.FLAG_ON;

    private final AdminQuerySupport support;
    private final RiderInfoService riderInfoService;

    public AdminSubjectQueryService(AdminQuerySupport support, RiderInfoService riderInfoService) {
        this.support = support;
        this.riderInfoService = riderInfoService;
    }

    @Transactional(readOnly = true)
    public AdminPageVo<AdminUserVo> listUsers(int page, int size, String keyword, Integer status) {
        int[] ps = support.sanitize(page, size);
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder(" WHERE u.role = :role");
        params.put("role", ROLE_USER);
        support.appendKeywordLike(where, params, keyword);
        if (status != null) {
            where.append(" AND u.status = :status");
            params.put("status", status);
        }
        String whereSql = where.toString();

        List<User> users = support.bindParams(
                support.em().createQuery("SELECT u FROM User u" + whereSql + " ORDER BY u.id DESC", User.class), params)
                .setFirstResult(ps[0] * ps[1])
                .setMaxResults(ps[1])
                .getResultList();
        long total = support.bindParams(
                support.em().createQuery("SELECT COUNT(u) FROM User u" + whereSql, Long.class), params)
                .getSingleResult();

        Map<Long, Long> orderCounts = support.groupCount(
                "SELECT o.userId, COUNT(o.id) FROM MissionOrder o WHERE o.userId IN :ids GROUP BY o.userId",
                support.userIds(users), Map.of());
        Map<Long, Long> disputeCounts = support.groupCount(
                "SELECT c.userId, COUNT(c.id) FROM OrderComplaint c WHERE c.userId IN :ids GROUP BY c.userId",
                support.userIds(users), Map.of());
        List<AdminUserVo> content = users.stream()
                .map(u -> new AdminUserVo(u.getId(), u.getUserName(), u.getStatus(),
                        orderCounts.getOrDefault(u.getId(), 0L),
                        disputeCounts.getOrDefault(u.getId(), 0L),
                        u.getCreateTime()))
                .collect(Collectors.toList());
        return AdminPageVo.of(content, ps[0], ps[1], total);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailVo getUserDetail(Long userId) {
        User user = support.requireSubject(userId, false);
        List<com.uav.order.pojo.entity.MissionOrder> orders = support.em().createQuery(
                "SELECT o FROM MissionOrder o WHERE o.userId = :userId ORDER BY o.createTime DESC",
                com.uav.order.pojo.entity.MissionOrder.class)
                .setParameter("userId", userId)
                .getResultList();
        List<OrderComplaint> complaints = support.em().createQuery(
                "SELECT c FROM OrderComplaint c WHERE c.userId = :userId ORDER BY c.createTime DESC",
                OrderComplaint.class)
                .setParameter("userId", userId)
                .getResultList();
        return new AdminUserDetailVo(user.getId(), user.getUserName(), user.getStatus(), user.getRole(),
                user.getCreateTime(), support.toOrderVos(orders), support.toComplaintVos(complaints));
    }

    @Transactional(readOnly = true)
    public AdminPageVo<AdminPilotVo> listPilots(int page, int size, String keyword) {
        int[] ps = support.sanitize(page, size);
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder(" WHERE u.role = :role");
        params.put("role", ROLE_PILOT);
        support.appendKeywordLike(where, params, keyword);
        String whereSql = where.toString();

        List<User> pilots = support.bindParams(
                support.em().createQuery("SELECT u FROM User u" + whereSql + " ORDER BY u.id DESC", User.class), params)
                .setFirstResult(ps[0] * ps[1])
                .setMaxResults(ps[1])
                .getResultList();
        long total = support.bindParams(
                support.em().createQuery("SELECT COUNT(u) FROM User u" + whereSql, Long.class), params)
                .getSingleResult();

        List<Long> ids = support.userIds(pilots);
        Map<Long, Long> uavCounts = support.groupCount(
                "SELECT r.userId, COUNT(r.id) FROM RiderUav r WHERE r.userId IN :ids GROUP BY r.userId",
                ids, Map.of());
        Map<Long, Long> onlineUavCounts = support.groupCount(
                "SELECT r.userId, COUNT(r.id) FROM RiderUav r, Uav u WHERE u.djiId = r.djiId "
                        + "AND u.onlineStatus = :flag AND r.userId IN :ids GROUP BY r.userId",
                ids, Map.of("flag", FLAG_ON));
        Map<Long, Long> completedCounts = support.groupCount(
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

    @Transactional(readOnly = true)
    public AdminPilotDetailVo getPilotDetail(Long userId) {
        User pilot = support.requireSubject(userId, true);

        List<RiderUav> bindings = support.em().createQuery(
                "SELECT r FROM RiderUav r WHERE r.userId = :userId ORDER BY r.createTime ASC, r.id ASC",
                RiderUav.class)
                .setParameter("userId", userId)
                .getResultList();

        List<com.uav.order.pojo.entity.MissionOrder> orders = support.em().createQuery(
                "SELECT o FROM MissionOrder o WHERE o.task.id IN "
                        + "(SELECT a.taskId FROM TaskAssignment a WHERE a.riderId = :riderId) "
                        + "OR o.selectedApplicationId IN "
                        + "(SELECT app.id FROM TaskApplication app WHERE app.riderId = :riderId) "
                        + "ORDER BY o.createTime DESC",
                com.uav.order.pojo.entity.MissionOrder.class)
                .setParameter("riderId", userId)
                .getResultList();

        long completed = support.em().createQuery(
                "SELECT COUNT(a) FROM TaskAssignment a WHERE a.riderId = :riderId AND a.completeTime IS NOT NULL",
                Long.class)
                .setParameter("riderId", userId)
                .getSingleResult();

        RiderInfoVO profile = riderInfoService.getInfo(userId);
        return new AdminPilotDetailVo(pilot.getId(), pilot.getUserName(), pilot.getStatus(), pilot.getRole(),
                completed, profile.idNumberMasked(), profile.idExpiryDate(), profile.idExpired(),
                profile.qualifications(), support.toDroneVos(bindings), support.toOrderVos(orders));
    }
}
