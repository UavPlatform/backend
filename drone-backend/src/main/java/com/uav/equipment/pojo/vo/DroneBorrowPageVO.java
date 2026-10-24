package com.uav.equipment.pojo.vo;
import java.util.List;
public record DroneBorrowPageVO(List<DroneBorrowVO> items, int page, boolean hasNext) { }
