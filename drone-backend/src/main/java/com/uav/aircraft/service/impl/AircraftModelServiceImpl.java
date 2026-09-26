package com.uav.aircraft.service.impl;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.aircraft.service.AircraftModelService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AircraftModelServiceImpl implements AircraftModelService {

    @Autowired
    private AircraftModelRepository aircraftModelRepository;

    @Override
    public List<AircraftModel> listEnabled() {
        return aircraftModelRepository.findByEnabledTrueOrderByModelCodeAsc();
    }

    @Override
    public AircraftModel requireSelectable(Long aircraftModelId) {
        if (aircraftModelId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.AIRCRAFT_MODEL_REQUIRED, "请选择机型");
        }
        return requireEnabled(aircraftModelId);
    }

    @Override
    public AircraftModel requireTransportCapable(Long aircraftModelId) {
        if (aircraftModelId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.AIRCRAFT_MODEL_REQUIRED);
        }
        AircraftModel model = requireEnabled(aircraftModelId);
        if (!Boolean.TRUE.equals(model.getTransportEnabled())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.AIRCRAFT_MODEL_NOT_TRANSPORTABLE);
        }
        return model;
    }

    private AircraftModel requireEnabled(Long aircraftModelId) {
        AircraftModel model = aircraftModelRepository.findById(aircraftModelId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.BAD_REQUEST, ApiErrorCode.AIRCRAFT_MODEL_NOT_FOUND));
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.AIRCRAFT_MODEL_NOT_FOUND);
        }
        return model;
    }
}
