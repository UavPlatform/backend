-- task 表补参考价与费用构成列（发布任务时由计价引擎算出并落库，供发布页/详情页展示，
-- 并作为飞手报价的区间锚点）。
--
-- 兼容 MySQL 8.x 与 H2 MySQL 模式（集成测试，R10）：金额用 DECIMAL、明细用 TEXT；
-- 一次性迁移（Flyway 版本化执行，无需存在性守卫）。

-- 平台参考价（元，2 位小数）
ALTER TABLE task ADD COLUMN reference_price DECIMAL(10, 2) NULL;

-- 参考价逐项明细 JSON（PriceDetailVO 序列化）
ALTER TABLE task ADD COLUMN price_detail VARCHAR(1000) NULL;

-- 超重等场景：需平台人工报价
ALTER TABLE task ADD COLUMN need_manual_quote BOOLEAN NOT NULL DEFAULT FALSE;
