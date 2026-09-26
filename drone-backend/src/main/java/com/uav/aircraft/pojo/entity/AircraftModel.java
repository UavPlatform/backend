package com.uav.aircraft.pojo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 平台机型目录（REQ-BACKEND-001 机型库 / ADR-0003 报价因子）。
 *
 * <p>由 Flyway {@code V2__aircraft_model.sql} 播种，种子仅为首批默认值；
 * {@code coefficient} 是报价公式 {@code quotedAmount = (距离费 + 重量费 + 类别费) × 机型系数}
 * 的乘子，改系数即改报价——禁止在本实体外提供任何「改价」入口。
 */
@Data
@Entity
@Table(name = "aircraft_model")
public class AircraftModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 型号编码（如 FC30、M350RTK），全表唯一。 */
    @Column(name = "model_code", nullable = false, length = 64)
    private String modelCode;

    /** 显示名（如 "DJI FlyCart 30"），面向用户展示。 */
    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    /** 最大载重（kg）。 */
    @Column(name = "max_payload_kg", nullable = false, precision = 8, scale = 2)
    private BigDecimal maxPayloadKg;

    /** 机型系数：参与 ADR-0003 报价公式的乘子。 */
    @Column(name = "coefficient", nullable = false, precision = 6, scale = 3)
    private BigDecimal coefficient;

    /** 是否启用：停用机型不可绑定、不可应征。 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    /** 是否可承接吊运：false 的机型可绑定设备，但不得用于吊运应征。 */
    @Column(name = "transport_enabled", nullable = false)
    private Boolean transportEnabled;
}
