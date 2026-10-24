package com.uav.flight.pojo.vo;
import java.util.List;
public record FlightTrackVO(List<FlightSampleVO> items, long nextAfter, boolean hasNext) { }
