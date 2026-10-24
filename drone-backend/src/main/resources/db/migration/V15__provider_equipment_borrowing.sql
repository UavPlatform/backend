-- REQ-PILOT-EXEC-005 / REQ-PROV-GOV-001: independent custody records; no order or money columns.
CREATE TABLE equipment_provider (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE provider_drone (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    provider_id BIGINT NOT NULL,
    aircraft_model_id BIGINT NOT NULL,
    serial_number VARCHAR(64) NOT NULL UNIQUE,
    airport_name VARCHAR(100) NOT NULL,
    latitude DECIMAL(9,6) NOT NULL,
    longitude DECIMAL(10,6) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    current_borrow_id VARCHAR(36) UNIQUE,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_provider_drone_provider FOREIGN KEY (provider_id) REFERENCES equipment_provider(id),
    CONSTRAINT fk_provider_drone_model FOREIGN KEY (aircraft_model_id) REFERENCES aircraft_model(id)
);
CREATE TABLE drone_borrow (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    drone_id VARCHAR(36) NOT NULL,
    pilot_id BIGINT NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    borrowed_at TIMESTAMP(6) NOT NULL,
    returned_at TIMESTAMP(6),
    CONSTRAINT uk_pilot_borrow_request UNIQUE (pilot_id, request_key),
    CONSTRAINT fk_borrow_drone FOREIGN KEY (drone_id) REFERENCES provider_drone(id),
    CONSTRAINT fk_borrow_pilot FOREIGN KEY (pilot_id) REFERENCES user(id)
);
ALTER TABLE provider_drone ADD CONSTRAINT fk_drone_current_borrow FOREIGN KEY (current_borrow_id) REFERENCES drone_borrow(id);
CREATE INDEX idx_provider_drone_catalog ON provider_drone(provider_id, enabled, created_at);
CREATE INDEX idx_borrow_pilot ON drone_borrow(pilot_id, borrowed_at);
CREATE INDEX idx_borrow_drone ON drone_borrow(drone_id, borrowed_at);
