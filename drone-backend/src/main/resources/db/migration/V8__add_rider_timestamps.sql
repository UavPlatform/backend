-- rider 表补 create_time / update_time（实体 Rider 已声明，原迁移未落库）。
--
-- 兼容 MySQL 8.x 与 H2 MySQL 模式（集成测试，R10）：一次性迁移（Flyway 版本化执行）。

ALTER TABLE rider ADD COLUMN create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE rider ADD COLUMN update_time TIMESTAMP NULL;
