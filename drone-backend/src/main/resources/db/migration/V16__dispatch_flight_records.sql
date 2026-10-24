-- REQ-PILOT-EXEC-007 / REQ-SHIP-EXEC-004: nullable real telemetry; no synthetic defaults.
CREATE TABLE dispatch_flight_connection (
 id VARCHAR(36) NOT NULL PRIMARY KEY, order_id VARCHAR(36) NOT NULL, pilot_id BIGINT NOT NULL,
 assignment_version BIGINT NOT NULL, request_key VARCHAR(64) NOT NULL,
 serial_number VARCHAR(64) NOT NULL, model_code VARCHAR(128) NOT NULL, model_name VARCHAR(128) NOT NULL,
 aircraft_model_id BIGINT, opened_at TIMESTAMP(6) NOT NULL, ended_at TIMESTAMP(6), end_reason VARCHAR(32),
 active_serial VARCHAR(64) UNIQUE, active_order_id VARCHAR(36) UNIQUE, latest_sample_id BIGINT,
 CONSTRAINT uk_flight_request UNIQUE (pilot_id, request_key),
 CONSTRAINT fk_flight_order FOREIGN KEY (order_id) REFERENCES dispatch_order(id),
 CONSTRAINT fk_flight_pilot FOREIGN KEY (pilot_id) REFERENCES user(id)
);
CREATE TABLE dispatch_flight_sample (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, connection_id VARCHAR(36) NOT NULL, order_id VARCHAR(36) NOT NULL,
 sequence_number BIGINT NOT NULL, fingerprint VARCHAR(64) NOT NULL, captured_at BIGINT NOT NULL,
 received_at TIMESTAMP(6) NOT NULL, latitude DOUBLE, longitude DOUBLE, altitude DOUBLE, speed DOUBLE,
 battery INT, flying BOOLEAN,
 CONSTRAINT uk_flight_sample UNIQUE (connection_id, sequence_number),
 CONSTRAINT fk_sample_connection FOREIGN KEY (connection_id) REFERENCES dispatch_flight_connection(id),
 CONSTRAINT fk_sample_order FOREIGN KEY (order_id) REFERENCES dispatch_order(id)
);
CREATE INDEX idx_flight_order ON dispatch_flight_connection(order_id, opened_at);
CREATE INDEX idx_flight_track ON dispatch_flight_sample(order_id, id);
