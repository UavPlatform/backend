package com.uav.admin.service;

import com.uav.admin.pojo.vo.AdminOrderVo;
import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.OrderStatus;
import com.uav.server.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Admin order list and detail queries. */
@Service
public class AdminOrderQueryService {

    private final AdminQuerySupport support;

    public AdminOrderQueryService(AdminQuerySupport support) {
        this.support = support;
    }

    @Transactional(readOnly = true)
    public AdminPageVo<AdminOrderVo> listOrders(int page, int size, String status, String orderNum, String taskNum) {
        int[] ps = support.sanitize(page, size);
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder();
        OrderStatus orderStatus = support.resolveOrderStatus(status);
        if (orderStatus != null) {
            where.append(" AND o.orderStatus = :status");
            params.put("status", orderStatus);
        }
        if (support.notBlank(orderNum)) {
            where.append(" AND o.orderNum = :orderNum");
            params.put("orderNum", orderNum.trim());
        }
        if (support.notBlank(taskNum)) {
            where.append(" AND o.task.taskNum = :taskNum");
            params.put("taskNum", taskNum.trim());
        }
        String whereSql = where.isEmpty() ? "" : "WHERE " + where.substring(5);

        List<com.uav.order.pojo.entity.MissionOrder> orders = support.bindParams(
                support.em().createQuery("SELECT o FROM MissionOrder o " + whereSql + " ORDER BY o.createTime DESC",
                        com.uav.order.pojo.entity.MissionOrder.class), params)
                .setFirstResult(ps[0] * ps[1])
                .setMaxResults(ps[1])
                .getResultList();
        long total = support.bindParams(
                support.em().createQuery("SELECT COUNT(o) FROM MissionOrder o " + whereSql, Long.class), params)
                .getSingleResult();

        var payRecords = support.payRecordsByOrderNum(support.orderNumsOf(orders));
        List<AdminOrderVo> content = orders.stream()
                .map(order -> support.toOrderVo(order, payRecords))
                .collect(Collectors.toList());
        return AdminPageVo.of(content, ps[0], ps[1], total);
    }

    @Transactional(readOnly = true)
    public AdminOrderVo getOrderDetail(String orderNum) {
        com.uav.order.pojo.entity.MissionOrder order = support.em()
                .createQuery("SELECT o FROM MissionOrder o WHERE o.orderNum = :orderNum",
                        com.uav.order.pojo.entity.MissionOrder.class)
                .setParameter("orderNum", orderNum)
                .getResultStream().findFirst()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));
        var payRecords = support.payRecordsByOrderNum(List.of(order.getOrderNum()));
        return support.toOrderVo(order, payRecords);
    }
}
