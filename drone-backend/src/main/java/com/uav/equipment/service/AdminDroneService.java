package com.uav.equipment.service;

import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.equipment.mapper.DroneBorrowRepository;
import com.uav.equipment.mapper.ProviderDroneRepository;
import com.uav.equipment.pojo.dto.ProviderDroneDTO;
import com.uav.equipment.pojo.entity.DroneBorrow;
import com.uav.equipment.pojo.entity.ProviderDrone;
import com.uav.equipment.pojo.vo.AdminDroneBorrowVO;
import com.uav.equipment.pojo.vo.AdminDroneVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** ADR-0011 / REQ-SUP-GOV-005: admin manages platform-owned rental drones; borrow status + latest order. */
@Service
public class AdminDroneService {
    static final int MAX_SIZE = 100;
    static final int MAX_AIRPORT_LENGTH = 100;
    /** Stages in which djifly may be connected and the order is being flown (same set as FlightRecordService OPEN_STAGES). */
    public static final Set<String> EXECUTING_STAGES = Set.of("PILOT_ARRIVED", "IN_TRANSIT");

    private final ProviderDroneRepository drones;
    private final DroneBorrowRepository borrows;
    private final AircraftModelRepository models;
    private final DispatchOrderRepository orders;
    private final UserRepository users;
    private final EquipmentCatalogService catalog;

    public AdminDroneService(ProviderDroneRepository drones, DroneBorrowRepository borrows, AircraftModelRepository models,
                             DispatchOrderRepository orders, UserRepository users, EquipmentCatalogService catalog) {
        this.drones = drones; this.borrows = borrows; this.models = models;
        this.orders = orders; this.users = users; this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public AdminPageVo<AdminDroneVO> list(int page, int size, String airportName) {
        if (page < 0 || size < 1) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        String airport = airportName == null || airportName.isBlank() ? null : airportName.trim();
        if (airport != null && airport.length() > MAX_AIRPORT_LENGTH) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        int pageSize = Math.min(size, MAX_SIZE);
        Page<ProviderDrone> result = drones.adminSearch(like(airport), PageRequest.of(page, pageSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return AdminPageVo.of(views(result.getContent()), page, pageSize, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AdminDroneVO get(String id) {
        return view(drones.findById(id).orElseThrow(EquipmentAccess::missing));
    }

    @Transactional
    public AdminDroneVO create(ProviderDroneDTO input) { return view(catalog.register(input)); }

    @Transactional
    public AdminDroneVO update(String id, ProviderDroneDTO input) { return view(catalog.update(id, input)); }

    @Transactional
    public AdminDroneVO enabled(String id, boolean enabled) { return view(catalog.enabled(id, enabled)); }

    @Transactional(readOnly = true)
    public AdminPageVo<AdminDroneBorrowVO> borrows(String droneId, int page, int size) {
        if (page < 0 || size < 1) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        if (!drones.existsById(droneId)) throw EquipmentAccess.missing();
        int pageSize = Math.min(size, MAX_SIZE);
        Page<DroneBorrow> rows = borrows.findByDroneId(droneId, PageRequest.of(page, pageSize,
                Sort.by(Sort.Order.desc("borrowedAt"), Sort.Order.desc("id"))));
        Map<Long, User> pilots = pilots(rows.stream().map(DroneBorrow::getPilotId).toList());
        return AdminPageVo.of(rows.stream().map(b -> new AdminDroneBorrowVO(b.getId(), b.getDroneId(), b.getPilotId(),
                name(pilots.get(b.getPilotId())), b.getBorrowedAt(), b.getReturnedAt())).toList(),
                page, pageSize, rows.getTotalElements());
    }

    private AdminDroneVO view(ProviderDrone d) { return views(List.of(d)).getFirst(); }

    private List<AdminDroneVO> views(List<ProviderDrone> rows) {
        Map<Long, AircraftModel> byModel = models.findAllById(rows.stream().map(ProviderDrone::getAircraftModelId).distinct().toList())
                .stream().collect(Collectors.toMap(AircraftModel::getId, Function.identity()));
        Map<String, DroneBorrow> current = borrows.findAllById(rows.stream().map(ProviderDrone::getCurrentBorrowId)
                .filter(Objects::nonNull).toList()).stream().collect(Collectors.toMap(DroneBorrow::getId, Function.identity()));
        Map<Long, User> pilots = pilots(current.values().stream().map(DroneBorrow::getPilotId).toList());
        // Serials are stored canonical (SerialNumbers.normalize) on both sides; one batched query each per page.
        List<String> serials = rows.stream().map(ProviderDrone::getSerialNumber).distinct().toList();
        Map<String, com.uav.dispatch.pojo.entity.DispatchOrder> latest = serials.isEmpty() ? Map.of()
                : orders.latestByExecutedSerials(serials).stream().collect(Collectors.toMap(
                        com.uav.dispatch.pojo.entity.DispatchOrder::getExecutedSerialNumber, Function.identity(), (a, b) -> a));
        Set<String> executingSerials = serials.isEmpty() ? Set.of() : new HashSet<>(orders.executingSerials(serials, EXECUTING_STAGES));
        return rows.stream().map(d -> {
            AircraftModel m = byModel.get(d.getAircraftModelId());
            DroneBorrow b = d.getCurrentBorrowId() == null ? null : current.get(d.getCurrentBorrowId());
            var o = latest.get(d.getSerialNumber());
            var latestOrder = o == null ? null : new AdminDroneVO.LatestOrder(o.getId(), o.getStage(), o.getCreatedAt());
            boolean executing = executingSerials.contains(d.getSerialNumber());
            return new AdminDroneVO(d.getId(),
                    new AdminDroneVO.Model(d.getAircraftModelId(), m == null ? null : m.getModelCode(), m == null ? null : m.getDisplayName()),
                    d.getSerialNumber(), d.getAirportName(), d.getLatitude(), d.getLongitude(), d.isEnabled(),
                    b == null ? "IN_STOCK" : "BORROWED",
                    b == null ? null : new AdminDroneVO.CurrentBorrow(b.getId(), b.getPilotId(), name(pilots.get(b.getPilotId())), b.getBorrowedAt()),
                    executing, latestOrder, d.getCreatedAt());
        }).toList();
    }

    private Map<Long, User> pilots(Collection<Long> ids) {
        return users.findAllById(new HashSet<>(ids)).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private static String name(User u) { return u == null ? null : u.getUserName(); }

    static String like(String keyword) {
        if (keyword == null) return null;
        return "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
