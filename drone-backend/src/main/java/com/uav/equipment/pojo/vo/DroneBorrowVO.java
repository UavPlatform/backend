package com.uav.equipment.pojo.vo;
import java.time.LocalDateTime;
public record DroneBorrowVO(String borrowId, ProviderDroneVO drone, Long pilotId, LocalDateTime borrowedAt, LocalDateTime returnedAt) { }
