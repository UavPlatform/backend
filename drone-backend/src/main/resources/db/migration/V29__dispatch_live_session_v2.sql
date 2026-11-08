-- ADR-0014: order-scoped pilot-controlled live session; V17 remains archived.
CREATE TABLE dispatch_live_session_v2 (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 order_id VARCHAR(36) NOT NULL,
 pilot_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL,
 opened_at TIMESTAMP(6) NOT NULL,
 ended_at TIMESTAMP(6),
 lease_expires_at BIGINT NOT NULL,
 CONSTRAINT uk_dispatch_live_v2_request UNIQUE (pilot_id, request_key),
 CONSTRAINT fk_dispatch_live_v2_order FOREIGN KEY (order_id) REFERENCES dispatch_order(id)
);
CREATE INDEX idx_dispatch_live_v2_order ON dispatch_live_session_v2(order_id, opened_at);
