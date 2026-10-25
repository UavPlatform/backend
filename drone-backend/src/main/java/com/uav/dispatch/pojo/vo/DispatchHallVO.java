package com.uav.dispatch.pojo.vo;
import java.util.List;

public record DispatchHallVO(List<DispatchOfferVO> items, int page, boolean hasNext) { }
