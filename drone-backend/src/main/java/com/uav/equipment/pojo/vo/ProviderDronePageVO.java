package com.uav.equipment.pojo.vo;
import java.util.List;
public record ProviderDronePageVO(List<ProviderDroneVO> items, int page, boolean hasNext) { }
