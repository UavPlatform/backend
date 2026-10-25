-- Temporary DISTANCE_V1 snapshots and the remaining delivery/settlement stages.
ALTER TABLE waybill_assessment ADD COLUMN quote_json LONGTEXT;
ALTER TABLE dispatch_order ADD COLUMN quote_json LONGTEXT;
ALTER TABLE dispatch_order ADD COLUMN started_at TIMESTAMP(6);
ALTER TABLE dispatch_order ADD COLUMN delivered_at TIMESTAMP(6);
ALTER TABLE dispatch_order ADD COLUMN receipt_at TIMESTAMP(6);
ALTER TABLE dispatch_order ADD COLUMN completed_at TIMESTAMP(6);
ALTER TABLE dispatch_order ADD COLUMN settled_at TIMESTAMP(6);
ALTER TABLE dispatch_order ADD COLUMN pilot_material_ids_json LONGTEXT;
ALTER TABLE dispatch_order ADD COLUMN receipt_material_ids_json LONGTEXT;
ALTER TABLE dispatch_order ADD COLUMN balance_amount DECIMAL(10,2);
ALTER TABLE dispatch_order ADD COLUMN settlement_reference VARCHAR(64);
ALTER TABLE dispatch_order ADD COLUMN review_score INT;
ALTER TABLE dispatch_order ADD COLUMN review_comment VARCHAR(500);
CREATE TABLE dispatch_material (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    uploader_id BIGINT NOT NULL,
    assignment_version BIGINT NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    object_key VARCHAR(255) NOT NULL UNIQUE,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    uploaded BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL,
    confirmed_at TIMESTAMP(6),
    CONSTRAINT fk_material_order FOREIGN KEY (order_id) REFERENCES dispatch_order(id),
    CONSTRAINT fk_material_user FOREIGN KEY (uploader_id) REFERENCES user(id)
);
CREATE INDEX idx_material_order ON dispatch_material(order_id, uploaded);
