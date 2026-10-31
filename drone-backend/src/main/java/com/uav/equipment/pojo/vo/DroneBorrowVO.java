package com.uav.equipment.pojo.vo;
import java.time.LocalDateTime;
/** Pilot-facing application/loan row. alreadyHasActiveLoan is set on create when pilot already holds a loan. */
public record DroneBorrowVO(
        String borrowId,
        String status,
        Long aircraftModelId,
        String modelName,
        ProviderDroneVO drone,
        String orderId,
        Long pilotId,
        LocalDateTime appliedAt,
        LocalDateTime borrowedAt,
        LocalDateTime returnedAt,
        String rejectReason,
        boolean alreadyHasActiveLoan) { }
