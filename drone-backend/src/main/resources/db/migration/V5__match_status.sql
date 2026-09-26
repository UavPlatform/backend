-- TASK-BACKEND-004 / REQ-BACKEND-001 撮合状态机与订单流程改造（ADR-0003 决定 3/4/6）。
-- 兼容 MySQL 8.x 与 H2 MySQL 模式（集成测试，R10）：列类型用 VARCHAR/TIMESTAMP/BIGINT，
-- 回填用 CASE WHEN（两侧方言通用），不使用任一侧特有的语法。

-- 1) 任务撮合子状态：SEEKING_RIDER → NEGOTIATING → AWAITING_PAYMENT →
--    AWAITING_RIDER_CONFIRM → CONFIRMED → PENDING_ACCEPTANCE → CLOSED。
ALTER TABLE task ADD COLUMN match_status VARCHAR(32) NULL;

-- 2) 订单撮合扩展列：锁定的应征、约定作业时间、双方确认时间（ADR-0003 决定 4 双确认门禁）。
--    金额锁定点从「发单」迁移到「用户选定应征」：totalAmount 在 select-rider 时写死 = quoted_amount。
ALTER TABLE mission_order ADD COLUMN selected_application_id BIGINT NULL;
ALTER TABLE mission_order ADD COLUMN scheduled_time TIMESTAMP NULL;
ALTER TABLE mission_order ADD COLUMN user_confirmed_at TIMESTAMP NULL;
ALTER TABLE mission_order ADD COLUMN rider_confirmed_at TIMESTAMP NULL;

-- 3) 存量回填：按 任务状态 × 订单状态 推导撮合子状态（与实现说明中的映射表一致）。
UPDATE task SET match_status = 'SEEKING_RIDER' WHERE match_status IS NULL;
UPDATE task SET match_status = 'PENDING_ACCEPTANCE'
 WHERE task_status = 'COMPLETED'
   AND EXISTS (SELECT 1 FROM mission_order o WHERE o.task_id = task.id AND o.order_status = 'WAITING_CONFIRM');
UPDATE task SET match_status = 'CLOSED'
 WHERE task_status = 'COMPLETED'
   AND EXISTS (SELECT 1 FROM mission_order o WHERE o.task_id = task.id AND o.order_status = 'COMPLETED');
UPDATE task SET match_status = 'CONFIRMED'
 WHERE task_status = 'IN_PROGRESS';
UPDATE task SET match_status = 'AWAITING_RIDER_CONFIRM'
 WHERE task_status = 'IDLE'
   AND EXISTS (SELECT 1 FROM mission_order o WHERE o.task_id = task.id AND o.order_status = 'PAID');
UPDATE task SET match_status = 'AWAITING_PAYMENT'
 WHERE task_status = 'IDLE'
   AND EXISTS (SELECT 1 FROM mission_order o WHERE o.task_id = task.id AND o.order_status = 'PENDING');
UPDATE task SET match_status = 'SEEKING_RIDER'
 WHERE EXISTS (SELECT 1 FROM mission_order o WHERE o.task_id = task.id AND o.order_status IN ('CANCELLED', 'REFUNDED'))
   AND task_status = 'IDLE';
