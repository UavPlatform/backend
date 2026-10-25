-- ADR-0009 / ADR-0007 / REQ-SUP-MON-005 / REQ-SUP-MON-007 / REQ-SHIP-EXEC-004.
-- Telemetry samples: device + capture-time de-duplication, optional payload weight, no FKs so the
-- table can be RANGE-partitioned by captured_at on MySQL (V21) and old months dropped after 180 days.
-- Connection records and the executed drone on the order live outside this table and are kept.
ALTER TABLE dispatch_flight_sample ADD COLUMN serial_number VARCHAR(64);
ALTER TABLE dispatch_flight_sample ADD COLUMN payload_kg DOUBLE;
UPDATE dispatch_flight_sample SET serial_number = (
    SELECT c.serial_number FROM dispatch_flight_connection c WHERE c.id = dispatch_flight_sample.connection_id);
ALTER TABLE dispatch_flight_sample MODIFY COLUMN serial_number VARCHAR(64) NOT NULL;
ALTER TABLE dispatch_flight_sample DROP CONSTRAINT fk_sample_connection;
ALTER TABLE dispatch_flight_sample DROP CONSTRAINT fk_sample_order;
CREATE UNIQUE INDEX uk_flight_sample_device ON dispatch_flight_sample(serial_number, captured_at);
CREATE INDEX idx_flight_sample_time ON dispatch_flight_sample(order_id, captured_at, id);
CREATE INDEX idx_flight_sample_connection ON dispatch_flight_sample(connection_id, sequence_number);

ALTER TABLE dispatch_order ADD COLUMN executed_serial_number VARCHAR(64);
ALTER TABLE dispatch_order ADD COLUMN executed_model_code VARCHAR(128);
ALTER TABLE dispatch_order ADD COLUMN executed_model_name VARCHAR(128);
ALTER TABLE dispatch_order ADD COLUMN executed_aircraft_model_id BIGINT;
