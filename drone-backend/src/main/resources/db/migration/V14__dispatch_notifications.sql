-- REQ-ALL-XC-005: recipient-scoped durable order notifications, atomic with business changes.
CREATE TABLE dispatch_notification (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    recipient_id BIGINT NOT NULL,
    order_id VARCHAR(36) NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    audience VARCHAR(16) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    title VARCHAR(100) NOT NULL,
    body VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    read_at TIMESTAMP(6),
    CONSTRAINT uk_dispatch_notice_event UNIQUE (recipient_id, event_key),
    CONSTRAINT fk_dispatch_notice_user FOREIGN KEY (recipient_id) REFERENCES user(id),
    CONSTRAINT fk_dispatch_notice_order FOREIGN KEY (order_id) REFERENCES dispatch_order(id)
);
CREATE INDEX idx_dispatch_notice_inbox ON dispatch_notification(recipient_id, created_at, id);
CREATE INDEX idx_dispatch_notice_unread ON dispatch_notification(recipient_id, read_at);
