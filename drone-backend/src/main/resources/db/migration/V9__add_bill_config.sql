-- 计费配置表：所有商业数值（费率、阶梯、夜间时段等）集中存放，运营可后台调整、
-- 无需重启；代码中只保留默认值兜底（见 BillConfigSeeder 播种）。
--
-- 兼容 MySQL 8.x 与 H2 MySQL 模式（集成测试，R10）：一次性迁移（Flyway 版本化执行）。

CREATE TABLE IF NOT EXISTS bill_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_key VARCHAR(64) NOT NULL,
    config_value VARCHAR(1000) NOT NULL,
    description VARCHAR(255) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    update_time TIMESTAMP NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bill_config_key UNIQUE (config_key)
);
