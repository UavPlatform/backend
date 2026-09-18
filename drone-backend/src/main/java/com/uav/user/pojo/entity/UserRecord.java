package com.uav.user.pojo.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_record")
@Data
@Schema(description = "用户观看记录（user_record 表）")
public class UserRecord {

    //单条记录的信息
    @Schema(description = "记录ID")
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Schema(description = "用户名")
    @Column(name = "user_name")
    private String userName;

    @Schema(description = "设备ID(DJI ID)")
    @Column(name = "dji_Id")
    private String djiId;

    @Schema(description = "观看开始时间（ISO-8601，如 2026-05-31T10:00:00）")
    @Column(name = "start_time")
    private LocalDateTime start_time;

    @Schema(description = "观看结束时间（ISO-8601；未结束时为 null）")
    @Column(name = "end_time")
    private LocalDateTime end_time;
}
