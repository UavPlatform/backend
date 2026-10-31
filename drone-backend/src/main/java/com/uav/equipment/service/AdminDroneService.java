package com.uav.equipment.service;

import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.airport.mapper.AirportRepository;
import com.uav.airport.pojo.entity.Airport;
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

/** ADR-0011 / ADR-0012 / REQ-SUP-GOV-005: admin manages platform drones; airport is default-bound. */
@Service
public class AdminDroneService {
    static final int MAX_SIZE = 100;
    static final int MAX_AIRPORT_LENGTH = 100;
    public static final Set<String> EXECUTING_STAGES = Set.of("PILOT_ARRIVED", "IN_TRANSIT");

    private final ProviderDroneRepository drones;
    private final DroneBorrowRepository borrows;
    private final AircraftModelRepository models;
    private final AirportRepository airports;
    private final DispatchOrderRepository orders;
    private final UserRepository users;
    private final EquipmentCatalogService catalog;

    public AdminDroneService(ProviderDroneRepository drones, DroneBorrowRepository borrows, AircraftModelRepository models,
                             AirportRepository airports, DispatchOrderRepository orders, UserRepository users,
                             EquipmentCatalogService catalog) {
        this.drones = drones; this.borrows = borrows; this.models = models; this.airports = airports;
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
        return AdminPageVo.of(rows.stream().map(b -> new AdminDroneBorrowVO(b.getId(), b.getDroneId(), b.getStatus(), b.getPilotId(),
                name(pilots.get(b.getPilotId())), b.getAppliedAt(), b.getBorrowedAt(), b.getReturnedAt(), b.getRejectReason())).toList(),
                page, pageSize, rows.getTotalElements());
    }

    private AdminDroneVO view(ProviderDrone d) { return views(List.of(d)).getFirst(); }

    private List<AdminDroneVO> views(List<ProviderDrone> rows) {
        Map<Long, AircraftModel> byModel = models.findAllById(rows.stream().map(ProviderDrone::getAircraftModelId).distinct().toList())
                .stream().collect(Collectors.toMap(AircraftModel::getId, Function.identity()));
        Map<Long, Airport> byAirport = airports.findAllById(rows.stream().map(ProviderDrone::getAirportId).distinct().toList())
                .stream().collect(Collectors.toMap(Airport::getId, Function.identity()));
        Map<String, DroneBorrow> current = borrows.findAllById(rows.stream().map(ProviderDrone::getCurrentBorrowId)
                .filter(Objects::nonNull).toList()).stream().collect(Collectors.toMap(DroneBorrow::getId, Function.identity()));
        Map<Long, User> pilots = pilots(current.values().stream().map(DroneBorrow::getPilotId).toList());
        List<String> serials = rows.stream().map(ProviderDrone::getSerialNumber).distinct().toList();
        Map<String, com.uav.dispatch.pojo.entity.DispatchOrder> latest = serials.isEmpty() ? Map.of()
                : orders.latestByExecutedSerials(serials).stream().collect(Collectors.toMap(
                        com.uav.dispatch.pojo.entity.DispatchOrder::getExecutedSerialNumber, Function.identity(), (a, b) -> a));
        Set<String> executingSerials = serials.isEmpty() ? Set.of() : new HashSet<>(orders.executingSerials(serials, EXECUTING_STAGES));
        return rows.stream().map(d -> {
            AircraftModel m = byModel.get(d.getAircraftModelId());
            Airport a = byAirport.get(d.getAirportId());
            DroneBorrow b = d.getCurrentBorrowId() == null ? null : current.get(d.getCurrentBorrowId());
            var o = latest.get(d.getSerialNumber());
            var latestOrder = o == null ? null : new AdminDroneVO.LatestOrder(o.getId(), o.getStage(), o.getCreatedAt());
            boolean executing = executingSerials.contains(d.getSerialNumber());
            return new AdminDroneVO(d.getId(),
                    new AdminDroneVO.Model(d.getAircraftModelId(), m == null ? null : m.getModelCode(), m == null ? null : m.getDisplayName()),
                    d.getSerialNumber(),
                    new AdminDroneVO.Airport(d.getAirportId(), a == null ? null : a.getName()),
                    d.isEnabled(),
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
