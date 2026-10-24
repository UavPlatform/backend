-- REQ-PILOT-MATCH-004 / REQ-PILOT-EXEC-006 / ADR-0007.
ALTER TABLE dispatch_order ADD COLUMN assigned_pilot_id BIGINT;
ALTER TABLE dispatch_order ADD COLUMN assignment_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE dispatch_order ADD CONSTRAINT fk_dispatch_pilot FOREIGN KEY (assigned_pilot_id) REFERENCES user(id);
CREATE INDEX idx_dispatch_hall ON dispatch_order(stage, funds_status, created_at);
CREATE INDEX idx_dispatch_pilot ON dispatch_order(assigned_pilot_id, updated_at);
CREATE TABLE dispatch_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    assignment_version BIGINT NOT NULL,
    pilot_id BIGINT NOT NULL,
    offer_revision BIGINT NOT NULL,
    accepted_at TIMESTAMP(6) NOT NULL,
    arrived_at TIMESTAMP(6),
    ended_at TIMESTAMP(6),
    end_reason VARCHAR(32),
    CONSTRAINT uk_dispatch_assignment UNIQUE (order_id, assignment_version),
    CONSTRAINT fk_assignment_order FOREIGN KEY (order_id) REFERENCES dispatch_order(id),
    CONSTRAINT fk_assignment_pilot FOREIGN KEY (pilot_id) REFERENCES user(id)
);
