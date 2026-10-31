-- REQ-PILOT-EXEC-005 / REQ-SUP-GOV-007: apply by model → admin assigns SN → loan; return without approval.
-- Platform not launched: existing rows (if any) backfilled as APPROVED/RETURNED loans.
-- H2 (test, MODE=MySQL) and MySQL: use correlated subqueries, not UPDATE...JOIN.

ALTER TABLE drone_borrow ADD COLUMN aircraft_model_id BIGINT NULL;
UPDATE drone_borrow SET aircraft_model_id = (
    SELECT d.aircraft_model_id FROM provider_drone d WHERE d.id = drone_borrow.drone_id
) WHERE aircraft_model_id IS NULL AND drone_id IS NOT NULL;
ALTER TABLE drone_borrow MODIFY COLUMN aircraft_model_id BIGINT NOT NULL;
ALTER TABLE drone_borrow ADD CONSTRAINT fk_borrow_model FOREIGN KEY (aircraft_model_id) REFERENCES aircraft_model(id);

ALTER TABLE drone_borrow ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'APPROVED';
UPDATE drone_borrow SET status = 'RETURNED' WHERE returned_at IS NOT NULL;
UPDATE drone_borrow SET status = 'APPROVED' WHERE returned_at IS NULL;

ALTER TABLE drone_borrow ADD COLUMN applied_at TIMESTAMP(6) NULL;
UPDATE drone_borrow SET applied_at = borrowed_at WHERE applied_at IS NULL;
ALTER TABLE drone_borrow MODIFY COLUMN applied_at TIMESTAMP(6) NOT NULL;

ALTER TABLE drone_borrow ADD COLUMN order_id VARCHAR(36) NULL;
ALTER TABLE drone_borrow ADD COLUMN reject_reason VARCHAR(500) NULL;
ALTER TABLE drone_borrow ADD COLUMN reviewed_by BIGINT NULL;
ALTER TABLE drone_borrow ADD COLUMN reviewed_at TIMESTAMP(6) NULL;

-- Pending applications have no assigned airframe yet.
ALTER TABLE drone_borrow MODIFY COLUMN drone_id VARCHAR(36) NULL;
ALTER TABLE drone_borrow MODIFY COLUMN borrowed_at TIMESTAMP(6) NULL;

CREATE INDEX idx_borrow_status_applied ON drone_borrow(status, applied_at);
CREATE INDEX idx_borrow_pilot_status ON drone_borrow(pilot_id, status, applied_at);

-- REQ-ALL-XC-005 (minimal): durable borrow/return notices; phone push still TODO.
-- recipient_id is JWT subject: user.id for pilots, admin.id for admins (no FK — admin lives outside user).
CREATE TABLE equipment_notification (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    recipient_id BIGINT NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    audience VARCHAR(16) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    title VARCHAR(100) NOT NULL,
    body VARCHAR(500) NOT NULL,
    borrow_id VARCHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    read_at TIMESTAMP(6),
    CONSTRAINT uk_equipment_notice_event UNIQUE (recipient_id, event_key),
    CONSTRAINT fk_equipment_notice_borrow FOREIGN KEY (borrow_id) REFERENCES drone_borrow(id)
);
CREATE INDEX idx_equipment_notice_inbox ON equipment_notification(recipient_id, created_at, id);
CREATE INDEX idx_equipment_notice_unread ON equipment_notification(recipient_id, read_at);
