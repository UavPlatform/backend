package com.uav.flight.pojo.vo;
public record FlightViewVO(FlightConnectionVO connection, FlightSampleVO latest, FlightSampleVO lastPosition, boolean stale, java.util.List<FlightConnectionVO> connectionHistory) { }
