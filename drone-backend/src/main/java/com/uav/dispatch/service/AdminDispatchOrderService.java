package com.uav.dispatch.service;

import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.dispatch.mapper.DispatchAssignmentRepository;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchAssignment;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.dispatch.pojo.vo.AdminDispatchOrderVO.Assignment;
import com.uav.dispatch.pojo.vo.AdminDispatchOrderVO.Connection;
import com.uav.dispatch.pojo.vo.AdminDispatchOrderVO.Detail;
import com.uav.dispatch.pojo.vo.AdminDispatchOrderVO.Party;
import com.uav.dispatch.pojo.vo.AdminDispatchOrderVO.Row;
import com.uav.dispatch.pojo.vo.AdminDispatchOrderVO.TimelineEvent;
import com.uav.flight.mapper.FlightConnectionRepository;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Admin-only read model over dispatch orders (supervision switch-over from the legacy /admin/orders). */
@Service
public class AdminDispatchOrderService {
    static final int MAX_SIZE = 100;

    private final DispatchOrderRepository orders;
    private final DispatchAssignmentRepository assignments;
    private final FlightConnectionRepository connections;
    private final UserRepository users;

    public AdminDispatchOrderService(DispatchOrderRepository orders, DispatchAssignmentRepository assignments,
                                     FlightConnectionRepository connections, UserRepository users) {
        this.orders = orders;
        this.assignments = assignments;
        this.connections = connections;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public AdminPageVo<Row> list(int page, int size, String stage, String fundsStatus,
                                 LocalDateTime from, LocalDateTime to, String keyword) {
        if (page < 0 || size < 1 || (from != null && to != null && !from.isBefore(to))) {
            throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        }
        int pageSize = Math.min(size, MAX_SIZE);
        Page<DispatchOrder> result = orders.adminSearch(blankToNull(stage), blankToNull(fundsStatus), from, to,
                like(keyword), PageRequest.of(page, pageSize,
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        Set<Long> ids = new HashSet<>();
        result.forEach(o -> { ids.add(o.getUserId()); if (o.getAssignedPilotId() != null) ids.add(o.getAssignedPilotId()); });
        Map<Long, User> byId = names(ids);
        List<Row> rows = result.getContent().stream().map(o -> new Row(o.getId(), o.getStage(), o.getFundsStatus(),
                party(o.getUserId(), byId), party(o.getAssignedPilotId(), byId), o.getDepositAmount(),
                o.getPilotServiceFee(), o.getExecutedSerialNumber(), o.getExecutedModelName(),
                o.getCreatedAt(), o.getUpdatedAt())).toList();
        return AdminPageVo.of(rows, page, pageSize, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Detail detail(String id) {
        DispatchOrder o = orders.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));
        List<DispatchAssignment> history = assignments.findByOrderIdOrderByAssignmentVersionAsc(id);
        Set<Long> ids = new HashSet<>();
        ids.add(o.getUserId());
        if (o.getAssignedPilotId() != null) ids.add(o.getAssignedPilotId());
        history.forEach(a -> ids.add(a.getPilotId()));
        Map<Long, User> byId = names(ids);
        List<Assignment> assignmentViews = history.stream().map(a -> new Assignment(a.getAssignmentVersion(),
                party(a.getPilotId(), byId), a.getAcceptedAt(), a.getArrivedAt(), a.getEndedAt(), a.getEndReason())).toList();
        List<Connection> connectionViews = connections.findByOrderIdOrderByOpenedAtAscIdAsc(id).stream()
                .map(c -> new Connection(c.getId(), c.getAssignmentVersion(), c.getPilotId(), c.getSerialNumber(),
                        c.getModelCode(), c.getModelName(), c.getAircraftModelId(), c.getOpenedAt(), c.getEndedAt(),
                        c.getEndReason())).toList();
        return new Detail(DispatchOrderViews.view(o), party(o.getUserId(), byId), party(o.getAssignedPilotId(), byId),
                timeline(o, history), assignmentViews, connectionViews);
    }

    static List<TimelineEvent> timeline(DispatchOrder o, List<DispatchAssignment> history) {
        List<TimelineEvent> events = new ArrayList<>();
        add(events, "CREATED", o.getCreatedAt(), o.getUserId());
        for (DispatchAssignment a : history) {
            add(events, "ACCEPTED", a.getAcceptedAt(), a.getPilotId());
            add(events, "ARRIVED", a.getArrivedAt(), a.getPilotId());
            add(events, a.getEndReason() == null ? "ASSIGNMENT_ENDED" : "ASSIGNMENT_ENDED:" + a.getEndReason(),
                    a.getEndedAt(), a.getPilotId());
        }
        add(events, "STARTED", o.getStartedAt(), o.getAssignedPilotId());
        add(events, "DELIVERED", o.getDeliveredAt(), o.getAssignedPilotId());
        add(events, "RECEIPT_CONFIRMED", o.getReceiptAt(), o.getUserId());
        add(events, "COMPLETED", o.getCompletedAt(), null);
        add(events, "SETTLED", o.getSettledAt(), null);
        events.sort(Comparator.comparing(TimelineEvent::at));
        return events;
    }

    private static void add(List<TimelineEvent> events, String name, LocalDateTime at, Long actor) {
        if (at != null) events.add(new TimelineEvent(name, at, actor));
    }

    private Map<Long, User> names(Set<Long> ids) {
        return users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static Party party(Long id, Map<Long, User> byId) {
        if (id == null) return null;
        User u = byId.get(id);
        return new Party(id, u == null ? null : u.getUserName());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String like(String keyword) {
        String k = blankToNull(keyword);
        if (k == null) return null;
        return "%" + k.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
