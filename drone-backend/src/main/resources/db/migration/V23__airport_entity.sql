-- ADR-0012 / REQ-SUP-GOV-005: airport is its own entity; drones reference airport_id and drop name/coords.
-- Platform not launched: existing drone rows (if any) are reassigned to the seed airport.

CREATE TABLE airport (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    latitude DECIMAL(9,6) NOT NULL,
    longitude DECIMAL(10,6) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL
);

INSERT INTO airport (id, name, latitude, longitude, enabled, created_at)
VALUES (1, '默认机场', 0.000000, 0.000000, TRUE, CURRENT_TIMESTAMP(6));

ALTER TABLE provider_drone ADD COLUMN airport_id BIGINT NULL;
UPDATE provider_drone SET airport_id = 1 WHERE airport_id IS NULL;
ALTER TABLE provider_drone MODIFY COLUMN airport_id BIGINT NOT NULL;
ALTER TABLE provider_drone ADD CONSTRAINT fk_provider_drone_airport
    FOREIGN KEY (airport_id) REFERENCES airport(id);

DROP INDEX idx_provider_drone_airport ON provider_drone;
ALTER TABLE provider_drone DROP COLUMN airport_name;
ALTER TABLE provider_drone DROP COLUMN latitude;
ALTER TABLE provider_drone DROP COLUMN longitude;
CREATE INDEX idx_provider_drone_airport ON provider_drone(airport_id);
