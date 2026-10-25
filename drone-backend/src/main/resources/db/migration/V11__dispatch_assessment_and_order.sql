-- REQ-SHIP-PUB-003 / ADR-0007. Separate tables preserve legacy quoted-order behavior.
CREATE TABLE waybill_assessment (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    fields_json LONGTEXT NOT NULL,
    evaluation_json LONGTEXT NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_assessment_owner FOREIGN KEY (user_id) REFERENCES user(id)
);
CREATE TABLE dispatch_order (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    assessment_id VARCHAR(36) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    fields_json LONGTEXT NOT NULL,
    deposit_amount DECIMAL(10,2) NOT NULL,
    pilot_service_fee DECIMAL(10,2) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    funds_status VARCHAR(32) NOT NULL,
    payment_reference VARCHAR(64),
    refund_reference VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_dispatch_request UNIQUE (user_id, request_key),
    CONSTRAINT fk_dispatch_owner FOREIGN KEY (user_id) REFERENCES user(id),
    CONSTRAINT fk_dispatch_assessment FOREIGN KEY (assessment_id) REFERENCES waybill_assessment(id)
);
CREATE INDEX idx_dispatch_owner_created ON dispatch_order(user_id, created_at);
