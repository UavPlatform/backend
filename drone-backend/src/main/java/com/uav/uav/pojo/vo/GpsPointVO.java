package com.uav.uav.pojo.vo;

import com.uav.uav.pojo.dto.UavStatusDto;
import com.uav.uav.pojo.entity.UavGpsRecord;

/**
 * 前端 GPS 轨迹 / 位置查询返回
 */
public record GpsPointVO(
        double longitude,
        double latitude,
        double altitude,
        double speed,
        int battery,
        int flightStatus,
        String operation,
        long timestamp
) {
    public static GpsPointVO from(UavGpsRecord record) {
        return new GpsPointVO(
                record.getLongitude(),
                record.getLatitude(),
                record.getAltitude(),
                record.getSpeed(),
                record.getBattery(),
                record.getFlightStatus(),
                record.getOperation(),
                record.getTimestamp()
        );
    }

    public static GpsPointVO from(UavStatusDto status) {
        return new GpsPointVO(
                status.getLongitude(),
                status.getLatitude(),
                status.getAltitude(),
                status.getSpeed(),
                status.getBattery(),
                status.getFlightStatus(),
                status.getOperation(),
                status.getTimestamp()
        );
    }
}
