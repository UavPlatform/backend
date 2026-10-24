package com.uav.equipment.service;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;

public final class EquipmentAccess {
    private EquipmentAccess() { }
    static BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.NO_PERMISSION, "记录不存在或不可访问"); }
    static BusinessException conflict(String message) { return new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.INVALID_PARAM, message); }
    static Pageable page(int page, String time) {
        if (page < 0 || page > 1000000) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        return PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, time, "id"));
    }
    public static int parse(String page) {
        try { return Integer.parseInt(page); }
        catch (NumberFormatException e) { throw new BusinessException(ApiErrorCode.INVALID_PARAM); }
    }
}
