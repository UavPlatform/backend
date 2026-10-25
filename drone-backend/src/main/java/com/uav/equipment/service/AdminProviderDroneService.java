package com.uav.equipment.service;

import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.equipment.mapper.EquipmentProviderRepository;
import com.uav.equipment.mapper.ProviderDroneRepository;
import com.uav.equipment.pojo.entity.EquipmentProvider;
import com.uav.equipment.pojo.entity.ProviderDrone;
import com.uav.equipment.pojo.vo.AdminProviderDroneVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Admin read-only view over provider_drone with provider/model/airport (ADR-0008: no airport CRUD). */
@Service
public class AdminProviderDroneService {
    static final int MAX_SIZE = 100;
    static final int MAX_AIRPORT_LENGTH = 100;

    private final ProviderDroneRepository drones;
    private final EquipmentProviderRepository providers;
    private final AircraftModelRepository models;

    public AdminProviderDroneService(ProviderDroneRepository drones, EquipmentProviderRepository providers,
                                     AircraftModelRepository models) {
        this.drones = drones;
        this.providers = providers;
        this.models = models;
    }

    @Transactional(readOnly = true)
    public AdminPageVo<AdminProviderDroneVO> list(int page, int size, Long providerId, String airportName) {
        if (page < 0 || size < 1 || (providerId != null && providerId < 1)) {
            throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        }
        String airport = airportName == null || airportName.isBlank() ? null : airportName.trim();
        if (airport != null && airport.length() > MAX_AIRPORT_LENGTH) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        int pageSize = Math.min(size, MAX_SIZE);
        Page<ProviderDrone> result = drones.adminSearch(providerId, like(airport), PageRequest.of(page, pageSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        Map<Long, EquipmentProvider> byProvider = providers.findAllById(
                result.stream().map(ProviderDrone::getProviderId).distinct().toList())
                .stream().collect(Collectors.toMap(EquipmentProvider::getId, Function.identity()));
        Map<Long, AircraftModel> byModel = models.findAllById(
                result.stream().map(ProviderDrone::getAircraftModelId).distinct().toList())
                .stream().collect(Collectors.toMap(AircraftModel::getId, Function.identity()));
        List<AdminProviderDroneVO> rows = result.getContent().stream().map(d -> {
            EquipmentProvider p = byProvider.get(d.getProviderId());
            AircraftModel m = byModel.get(d.getAircraftModelId());
            return new AdminProviderDroneVO(d.getId(),
                    new AdminProviderDroneVO.Provider(d.getProviderId(), p == null ? null : p.getDisplayName(), p != null && p.isEnabled()),
                    new AdminProviderDroneVO.Model(d.getAircraftModelId(), m == null ? null : m.getModelCode(), m == null ? null : m.getDisplayName()),
                    d.getSerialNumber(), d.getAirportName(), d.getLatitude(), d.getLongitude(), d.isEnabled(),
                    d.getCurrentBorrowId() != null, d.getCreatedAt());
        }).toList();
        return AdminPageVo.of(rows, page, pageSize, result.getTotalElements());
    }

    static String like(String keyword) {
        if (keyword == null) return null;
        return "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
