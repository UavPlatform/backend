-- TASK-BACKEND-001 / REQ-BACKEND-001 机型库：平台机型目录 + 飞手设备机型映射。
-- 兼容 MySQL 8.x 与 H2 MySQL 模式（集成测试，R10）：布尔列用 BOOLEAN
-- （MySQL 等价 TINYINT(1)，H2 原生 BOOLEAN），避免 TINYINT 在两侧类型校验不一致。

CREATE TABLE IF NOT EXISTS aircraft_model (
    id BIGINT NOT NULL AUTO_INCREMENT,
    model_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    max_payload_kg DECIMAL(8, 2) NOT NULL,
    coefficient DECIMAL(6, 3) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    transport_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT uk_aircraft_model_code UNIQUE (model_code)
);

-- 种子数据：平台首批机型（联调用默认值，系数参与 ADR-0003 报价公式
-- quotedAmount = (距离费 + 重量费 + 类别费) × 机型系数，后续由运营维护）。
INSERT INTO aircraft_model (id, model_code, display_name, max_payload_kg, coefficient, enabled, transport_enabled)
VALUES (1, 'FC30', 'DJI FlyCart 30', 30.00, 1.000, TRUE, TRUE),
       (2, 'M350RTK', 'DJI Matrice 350 RTK', 2.70, 1.200, TRUE, TRUE);

-- 飞手绑定设备映射到机型：可空迁移，存量绑定（注册旧路径）保持未映射，
-- 由吊运应征门禁（AIRCRAFT_MODEL_REQUIRED）拦截，映射后方可应征。
ALTER TABLE rider_uav ADD COLUMN aircraft_model_id BIGINT NULL;
ALTER TABLE rider_uav ADD CONSTRAINT fk_rider_uav_aircraft_model
    FOREIGN KEY (aircraft_model_id) REFERENCES aircraft_model (id);
