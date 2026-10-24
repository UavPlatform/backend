-- REQ-SHIP-EXEC-002 / REQ-PILOT-EXEC-006: never reuse old task_num for dispatch UUIDs.
ALTER TABLE chat_session ADD COLUMN dispatch_order_id VARCHAR(36) NULL;
ALTER TABLE chat_session ADD COLUMN dispatch_assignment_version BIGINT NULL;
CREATE UNIQUE INDEX uk_chat_dispatch_assignment ON chat_session (dispatch_order_id, dispatch_assignment_version);
