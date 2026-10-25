package com.uav.aircraft.service;

import com.uav.admin.pojo.vo.AdminPageVo;
import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.dto.AdminAircraftModelDTO;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.aircraft.pojo.vo.AdminAircraftModelVO;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.equipment.mapper.ProviderDroneRepository;
import com.uav.flight.mapper.FlightConnectionRepository;
import com.uav.rider.mapper.RiderQualificationRepository;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.task.mapper.TaskApplicationRepository;
import com.uav.user.mapper.RiderUavRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** REQ-SUP-GOV-006: admin CRUD for aircraft_model (create/edit/list/enable-disable/delete). */
@Service
public class AdminAircraftModelService {
    static final int MAX_SIZE = 100;
    /** New models keep the historic default coefficient until step-4 quote cleanup. */
    static final BigDecimal DEFAULT_COEFFICIENT = new BigDecimal("1.000");

    private final AircraftModelRepository models;
    private final ProviderDroneRepository drones;
    private final RiderUavRepository riderUavs;
    private final RiderQualificationRepository qualifications;
    private final FlightConnectionRepository flights;
    private final DispatchOrderRepository orders;
    private final TaskApplicationRepository applications;

    public AdminAircraftModelService(AircraftModelRepository models, ProviderDroneRepository drones,
                                     RiderUavRepository riderUavs, RiderQualificationRepository qualifications,
                                     FlightConnectionRepository flights, DispatchOrderRepository orders,
                                     TaskApplicationRepository applications) {
        this.models = models;
        this.drones = drones;
        this.riderUavs = riderUavs;
        this.qualifications = qualifications;
        this.flights = flights;
        this.orders = orders;
        this.applications = applications;
    }

    @Transactional(readOnly = true)
    public AdminPageVo<AdminAircraftModelVO> list(int page, int size) {
        if (page < 0 || size < 1) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        int pageSize = Math.min(size, MAX_SIZE);
        Page<AircraftModel> result = models.findAllByOrderByModelCodeAsc(PageRequest.of(page, pageSize));
        return AdminPageVo.of(result.getContent().stream().map(AdminAircraftModelVO::from).toList(),
                page, pageSize, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AdminAircraftModelVO get(Long id) {
        return AdminAircraftModelVO.from(require(id));
    }

    @Transactional
    public AdminAircraftModelVO create(AdminAircraftModelDTO input) {
        String code = code(input.modelCode());
        if (models.existsByModelCodeIgnoreCase(code)) throw conflict("型号代码已存在");
        AircraftModel m = new AircraftModel();
        apply(m, code, input);
        m.setCoefficient(DEFAULT_COEFFICIENT);
        m.setEnabled(true);
        try {
            return AdminAircraftModelVO.from(models.saveAndFlush(m));
        } catch (DataIntegrityViolationException e) {
            throw conflict("型号代码已存在");
        }
    }

    @Transactional
    public AdminAircraftModelVO update(Long id, AdminAircraftModelDTO input) {
        AircraftModel m = require(id);
        String code = code(input.modelCode());
        if (models.existsByModelCodeIgnoreCaseAndIdNot(code, id)) throw conflict("型号代码已存在");
        apply(m, code, input);
        try {
            return AdminAircraftModelVO.from(models.saveAndFlush(m));
        } catch (DataIntegrityViolationException e) {
            throw conflict("型号代码已存在");
        }
    }

    @Transactional
    public AdminAircraftModelVO enabled(Long id, boolean enabled) {
        AircraftModel m = require(id);
        m.setEnabled(enabled);
        return AdminAircraftModelVO.from(models.saveAndFlush(m));
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        if (referenced(id)) {
            throw conflict("机型已被引用，请改为停用");
        }
        try {
            models.deleteById(id);
            models.flush();
        } catch (DataIntegrityViolationException e) {
            throw conflict("机型已被引用，请改为停用");
        }
    }

    private boolean referenced(Long id) {
        return drones.existsByAircraftModelId(id)
                || riderUavs.existsByAircraftModelId(id)
                || qualifications.existsByAircraftModelId(id)
                || flights.existsByAircraftModelId(id)
                || orders.existsByExecutedAircraftModelId(id)
                || applications.existsByAircraftModelId(id);
    }

    private AircraftModel require(Long id) {
        if (id == null) throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.AIRCRAFT_MODEL_NOT_FOUND);
        return models.findById(id).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.AIRCRAFT_MODEL_NOT_FOUND));
    }

    private static void apply(AircraftModel m, String code, AdminAircraftModelDTO input) {
        m.setModelCode(code);
        m.setDisplayName(input.displayName().trim());
        m.setMaxPayloadKg(input.maxPayloadKg());
        m.setTransportEnabled(input.transportEnabled());
    }

    private static String code(String raw) {
        if (raw == null) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        String value = raw.trim();
        if (value.isEmpty() || value.length() > 64) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        return value;
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.INVALID_PARAM, message);
    }
}
