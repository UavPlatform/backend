package com.uav.dispatch.service;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

@Component @ConditionalOnProperty(name = "dispatch.nearby.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class DispatchNearbyScheduler {
    private final DispatchOrderRepository orders;
    private final DispatchNearbyService nearby;
    public DispatchNearbyScheduler(DispatchOrderRepository orders, DispatchNearbyService nearby) { this.orders = orders; this.nearby = nearby; }
    // Separate per-order transactions serialize all worker instances with claim/cancel and deduplicate recipients.
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void deliverWaiting() {
        for (int page = 0; ; page++) {
            var slice = orders.findByStageAndFundsStatusOrderByCreatedAtAsc("WAITING_ACCEPTANCE", "PAID", PageRequest.of(page, 100, Sort.by("id")));
            for (var order : slice) {
                try { nearby.process(order.getId()); }
                catch (RuntimeException retryable) { LoggerFactory.getLogger(getClass()).warn("附近派单暂未完成，后续重试，订单 {}", order.getId()); }
            }
            if (!slice.hasNext()) return;
        }
    }
}
