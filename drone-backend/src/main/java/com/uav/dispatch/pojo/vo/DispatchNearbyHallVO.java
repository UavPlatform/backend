package com.uav.dispatch.pojo.vo;
import java.util.List;
public record DispatchNearbyHallVO(List<DispatchNearbyOfferVO> items, int page, boolean hasNext,
        double radiusKm, String locationState, Long locationExpiresAt) { }
