package com.uav.flight.pojo.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
@Entity @Table(name = "dispatch_flight_connection") @Getter @Setter
public class FlightConnection {
 @Id @Column(length = 36) private String id;
 @Column(nullable = false, length = 36) private String orderId;
 @Column(nullable = false) private Long pilotId;
 @Column(nullable = false) private long assignmentVersion;
 @Column(nullable = false, length = 64) private String requestKey;
 @Column(nullable = false, length = 64) private String serialNumber;
 @Column(nullable = false, length = 128) private String modelCode;
 @Column(nullable = false, length = 128) private String modelName;
 private Long aircraftModelId;
 @Column(nullable = false) private LocalDateTime openedAt;
 private LocalDateTime endedAt;
 @Column(length = 32) private String endReason;
 @Column(unique = true, length = 64) private String activeSerial;
 @Column(unique = true, length = 36) private String activeOrderId;
 private Long latestSampleId;
}
