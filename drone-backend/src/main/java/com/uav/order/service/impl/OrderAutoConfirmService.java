package com.uav.order.service.impl;

import com.uav.order.mapper.OrderRepository;
import com.uav.order.pojo.entity.MissionOrder;
import com.uav.server.enums.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 验收超时自动确认（1B-9a 骨架，全景 P2-19：超时机制长期缺位）。
 *
 * <p><b>开关与参数</b>：
 * <ul>
 *   <li>{@code order.auto-confirm-enabled} —— 默认 <b>false</b>（关闭时零行为变化，仅打点）；</li>
 *   <li>{@code order.auto-confirm-hours} —— WAITING_CONFIRM 超过该小时数后自动确认，默认 72；</li>
 *   <li>{@code order.auto-confirm-interval-ms} —— 调度间隔，默认 600000（10 分钟）。</li>
 * </ul>
 * <p>开启后：将超时的 WAITING_CONFIRM 订单置为 COMPLETED，executeResult=AUTO_CONFIRM 留痕。
 * 注意：本骨架只落订单终态，不做结算/通知联动（1B 后续任务扩展点）。
 */
@Slf4j
@Service
public class OrderAutoConfirmService {

    private final OrderRepository orderRepository;

    @Value("${order.auto-confirm-enabled:false}")
    private boolean enabled;

    @Value("${order.auto-confirm-hours:72}")
    private int autoConfirmHours;

    public OrderAutoConfirmService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Scheduled(fixedDelayString = "${order.auto-confirm-interval-ms:600000}",
            initialDelayString = "${order.auto-confirm-initial-delay-ms:600000}")
    public void scheduledTick() {
        try {
            autoConfirmExpiredOrders();
        } catch (Exception e) {
            log.error("验收超时自动确认调度失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 按配置执行一次超时自动确认（调度入口；开关关闭时恒为 0）。
     */
    @Transactional
    public int autoConfirmExpiredOrders() {
        return autoConfirmExpiredOrders(enabled, autoConfirmHours);
    }

    /**
     * 核心逻辑（参数化以便测试直调）：enabled=false 时零行为变化。
     *
     * @return 本次自动确认的订单数
     */
    @Transactional
    public int autoConfirmExpiredOrders(boolean enabled, int hours) {
        if (!enabled) {
            return 0; // 默认关闭：零行为变化
        }
        LocalDateTime cutoff = LocalDateTime.now().minusHours(hours);
        List<MissionOrder> expired = orderRepository
                .findByOrderStatusAndUpdateTimeBefore(OrderStatus.WAITING_CONFIRM, cutoff);
        for (MissionOrder order : expired) {
            order.setOrderStatus(OrderStatus.COMPLETED);
            order.setExecutedAt(LocalDateTime.now());
            order.setExecuteResult("AUTO_CONFIRM");
            orderRepository.save(order);
        }
        if (!expired.isEmpty()) {
            log.info("[AUTO CONFIRM] 验收超时自动确认 {} 笔订单（阈值 {} 小时）", expired.size(), hours);
        }
        return expired.size();
    }
}
