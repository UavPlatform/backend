-- 飞手资料与驾驶资质（重写自 PR #14 可用部分；REQ-APP-001「飞手：资料维护」、REQ-FRONTEND-001 飞手详情）。
-- 兼容 MySQL 8.x 与 H2 MySQL 模式（集成测试，R10）：列类型用 VARCHAR/DATE/TIMESTAMP/BIGINT，
-- 约束用具名 CONSTRAINT，不使用 information_schema + PREPARE 等任一侧特有写法（ADR-0001：Flyway 唯一 DDL 源）。

-- 1) rider 资料扩展：身份证号（敏感，仅服务端持有，接口一律脱敏返回）、证件有效期、审计时间。
--    age 改为可空：资料按 PATCH 语义分次填写，首次保存时不强制年龄（实体 Integer age）。
ALTER TABLE rider MODIFY COLUMN age INT NULL;
ALTER TABLE rider ADD COLUMN id_number VARCHAR(32) NULL;
ALTER TABLE rider ADD COLUMN id_expiry_date DATE NULL;
ALTER TABLE rider ADD COLUMN create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE rider ADD COLUMN update_time TIMESTAMP NULL;

-- 2) 飞手驾驶资质：维度对齐平台机型库（aircraft_model，V2），不再另设机型类别/重量等级枚举。
--    同一飞手同一机型仅一条资质（执照等级 license_grade：VLOS / BVLOS / TEACHER）。
CREATE TABLE IF NOT EXISTS rider_qualification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    aircraft_model_id BIGINT NOT NULL,
    license_grade VARCHAR(32) NOT NULL,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_rider_qualification_user_model UNIQUE (user_id, aircraft_model_id)
);
ALTER TABLE rider_qualification ADD CONSTRAINT fk_rider_qualification_user
    FOREIGN KEY (user_id) REFERENCES user (id);
ALTER TABLE rider_qualification ADD CONSTRAINT fk_rider_qualification_aircraft_model
    FOREIGN KEY (aircraft_model_id) REFERENCES aircraft_model (id);
