package com.uav.equipment.pojo.vo;
import java.util.List;
public record ProviderAccountPageVO(List<ProviderAccountVO> items, int page, boolean hasNext) { }
