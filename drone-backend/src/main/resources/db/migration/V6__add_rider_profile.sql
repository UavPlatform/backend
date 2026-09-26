-- 飞手资料：资质字段、自我介绍列名对齐、资质表与查询索引。
--
-- 兼容 MySQL 8.x 与 H2 MySQL 模式（集成测试，R10）：不使用任一侧特有语法；
-- ALTER/CREATE 均为一次性迁移（Flyway 版本化执行，无需存在性守卫）。

-- 自我介绍列名对齐实体（Rider.selfIntroduction → @Column("self_intro")）
ALTER TABLE rider RENAME COLUMN self_introduction TO self_intro;

-- 资质字段：可空，存量行不填
ALTER TABLE rider ADD COLUMN cert_number VARCHAR(64) NULL;
ALTER TABLE rider ADD COLUMN cert_valid_from DATE NULL;
ALTER TABLE rider ADD COLUMN cert_valid_until DATE NULL;

-- 无人机驾驶资质（一飞手多条：类别 / 执照等级 / 重量等级）
CREATE TABLE IF NOT EXISTS rider_aircraft_qualification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    category VARCHAR(32) NOT NULL,
    license VARCHAR(32) NOT NULL,
    weight VARCHAR(32) NOT NULL,
    PRIMARY KEY (id)
);

-- 查询索引
CREATE INDEX idx_qual_user_id ON rider_aircraft_qualification (user_id);
CREATE INDEX idx_user_role ON user (role);
CREATE INDEX idx_assign_rider_complete ON task_assignment (rider_id, complete_time);
CREATE INDEX idx_assign_rider_accept ON task_assignment (rider_id, accept_time);
CREATE INDEX idx_assign_task ON task_assignment (task_id);
