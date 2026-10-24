-- REQ-PILOT-MATCH-004 / REQ-SHIP-MATCH-004: opted-in latest fix and waiting round timer.
CREATE TABLE dispatch_pilot_position (
    pilot_id BIGINT NOT NULL PRIMARY KEY,
    session_key VARCHAR(36) NOT NULL,
    latitude DOUBLE NULL,
    longitude DOUBLE NULL,
    accuracy_meters DOUBLE NULL,
    captured_at BIGINT NULL,
    received_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    CONSTRAINT fk_dispatch_position_user FOREIGN KEY (pilot_id) REFERENCES user(id)
);
CREATE INDEX idx_dispatch_position_expiry ON dispatch_pilot_position(expires_at);
ALTER TABLE dispatch_order ADD COLUMN nearby_waiting_since TIMESTAMP(6) NULL;
UPDATE dispatch_order SET nearby_waiting_since = updated_at WHERE stage = 'WAITING_ACCEPTANCE' AND funds_status = 'PAID';
