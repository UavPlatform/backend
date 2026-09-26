-- TASK-BACKEND-003 / REQ-BACKEND-001 飞手应征与报价（ADR-0003 多飞手应征 + 平台计价 SSOT）。
-- 兼容 MySQL 8.x 与 H2 MySQL 模式（集成测试，R10）：金额用 DECIMAL、时间用 TIMESTAMP，
-- 不使用任一侧特有的语法；ALTER/CREATE 均为一次性迁移（Flyway 版本化执行）。

-- 任务货物字段：ADR-0003 报价公式
-- quotedAmount = (距离费 + 重量费 + 类别费) × 机型系数 的必需因子。
-- 可空：存量任务与非吊运任务不填；吊运应征时缺重量会被拒绝计价（INVALID_PARAM）。
ALTER TABLE task ADD COLUMN cargo_weight_kg DECIMAL(8, 2) NULL;
ALTER TABLE task ADD COLUMN cargo_category VARCHAR(32) NULL;

-- 应征记录：一任务多条，同一飞手同一任务仅一条（重复应征=更新原记录并重新计价）。
-- status 为撮合子状态（ADR-0003 决定 6）：ACTIVE=应征中；SELECTED/CLOSED 由
-- TASK-BACKEND-004 的用户选定（select-rider）使用——选定一条后其余自动失效。
CREATE TABLE IF NOT EXISTS task_application (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    rider_id BIGINT NOT NULL,
    aircraft_model_id BIGINT NOT NULL,
    quoted_amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_task_application_task_rider UNIQUE (task_id, rider_id)
);
CREATE INDEX idx_task_application_task_id ON task_application (task_id);
ALTER TABLE task_application ADD CONSTRAINT fk_task_application_task
    FOREIGN KEY (task_id) REFERENCES task (id);
ALTER TABLE task_application ADD CONSTRAINT fk_task_application_aircraft_model
    FOREIGN KEY (aircraft_model_id) REFERENCES aircraft_model (id);
