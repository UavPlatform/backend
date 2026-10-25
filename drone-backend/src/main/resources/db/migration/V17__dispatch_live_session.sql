-- REQ-PILOT-EXEC-004 / REQ-SHIP-EXEC-004: separate rooms for each flight/broadcast.
CREATE TABLE dispatch_live_session (
 id VARCHAR(36) NOT NULL PRIMARY KEY, order_id VARCHAR(36) NOT NULL, connection_id VARCHAR(36) NOT NULL,
 pilot_id BIGINT NOT NULL, request_key VARCHAR(64) NOT NULL, state VARCHAR(16) NOT NULL,
 opened_at TIMESTAMP(6) NOT NULL, ended_at TIMESTAMP(6), lease_expires_at BIGINT NOT NULL,
 active_connection_id VARCHAR(36) UNIQUE,
 CONSTRAINT uk_dispatch_live_request UNIQUE (pilot_id, request_key),
 CONSTRAINT fk_dispatch_live_order FOREIGN KEY (order_id) REFERENCES dispatch_order(id),
 CONSTRAINT fk_dispatch_live_connection FOREIGN KEY (connection_id) REFERENCES dispatch_flight_connection(id)
);
CREATE INDEX idx_dispatch_live_order ON dispatch_live_session(order_id, opened_at);
