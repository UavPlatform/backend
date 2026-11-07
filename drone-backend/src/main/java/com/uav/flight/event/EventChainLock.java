package com.uav.flight.event;

import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Every event append, gap repair, reconciliation read, and later settlement must take this lock:
 * the order row first, then that order's chains in chain_id order. Do not lock a chain on its own.
 */
@Component
public class EventChainLock {
    private final DispatchOrderRepository orders;
    private final JdbcTemplate jdbc;

    public EventChainLock(DispatchOrderRepository orders, JdbcTemplate jdbc) {
        this.orders = orders;
        this.jdbc = jdbc;
    }

    public DispatchOrder lock(String orderId) {
        var order = orders.lockOrder(orderId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));
        jdbc.queryForList("SELECT chain_id FROM flight_event_chain WHERE order_id = ? ORDER BY chain_id FOR UPDATE",
                String.class, orderId);
        return order;
    }
}
