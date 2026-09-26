-- TASK-BACKEND-005 / REQ-BACKEND-001 聊天会话绑定 taskNum（ADR-0003 决定 1「订单内洽谈」）。
-- 兼容 MySQL 8.x 与 H2 MySQL 模式（集成测试，R10）：只用两侧通用的 ALTER/CREATE INDEX 语法。
-- 不建外键：chat_session 不参与任务删除守卫，任务编号仅作聚合与去重键（与 chat 表既有无 FK 设计一致）。

-- 任务编号：可空。存量会话与非任务会话为 NULL；任务内嵌会话写入对应 task_num。
ALTER TABLE chat_session ADD COLUMN task_num VARCHAR(64) NULL;

-- 可选 applicationId 关联（ADR-0003）：创建任务会话时对应应征记录存在则带上。
ALTER TABLE chat_session ADD COLUMN application_id BIGINT NULL;

-- 按 taskNum 聚合会话（GET /task/{taskNum}/chat-sessions）与「同任务+同飞手」去重复用查询。
CREATE INDEX idx_chat_session_task_num ON chat_session (task_num);
