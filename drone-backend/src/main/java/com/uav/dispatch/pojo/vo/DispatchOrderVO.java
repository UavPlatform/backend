package com.uav.dispatch.pojo.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record DispatchOrderVO(String orderId, String stage, String fundsStatus, String paymentMode,
                              BigDecimal depositAmount, BigDecimal pilotServiceFee, String currency,
                              String paymentReference, String refundReference, Map<String, String> fields,
                              LocalDateTime createdAt, LocalDateTime updatedAt,
                              Long assignedPilotId, long assignmentVersion, DispatchBillVO bill,
                              LocalDateTime startedAt, LocalDateTime deliveredAt, LocalDateTime receiptAt,
                              DispatchExecutedDroneVO executedDrone,
                              LocalDateTime depositPaidAt, LocalDateTime waitingSince,
                              LocalDateTime acceptedAt, LocalDateTime arrivedAt,
                              String deliveryNote) { }
