-- App「我的交易」查询索引：卖方口径走订单成交指针 selected_application_id → task_application.rider_id
-- （ADR-0003 决定 3：用户选定应征时写入），这两条路径原先无索引，每次都是全表扫描。
-- 兼容 MySQL 8.x 与 H2 MySQL 模式（集成测试，R10）：MySQL 8 不支持 CREATE INDEX IF NOT EXISTS，
-- 故按 V3 的写法写裸语句（Flyway 版本化执行，一次性）。

-- 1) 应征按飞手：先定位我的应征，再匹配订单成交指针（我卖出的 / 我的交易）。
CREATE INDEX idx_task_application_rider_id ON task_application (rider_id);

-- 2) 订单按成交指针（我的交易 / 我卖出的）。
CREATE INDEX idx_mission_order_selected_application_id ON mission_order (selected_application_id);
