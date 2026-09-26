-- 用户收货地址簿（发布任务时选取起降点/收货信息）。
-- 列与 Address 实体一致（@Table("user_address")）。
--
-- 兼容 MySQL 8.x 与 H2 MySQL 模式（集成测试，R10）：一次性迁移（Flyway 版本化执行）。

CREATE TABLE IF NOT EXISTS user_address (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    contact_name VARCHAR(255) NOT NULL,
    contact_phone VARCHAR(255) NOT NULL,
    province VARCHAR(255) NULL,
    city VARCHAR(255) NULL,
    district VARCHAR(255) NULL,
    detail VARCHAR(255) NOT NULL,
    latitude DOUBLE NULL,
    longitude DOUBLE NULL,
    label VARCHAR(255) NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMP NULL,
    update_time TIMESTAMP NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_address_user ON user_address (user_id);
