-- ADR-0011 / REQ-SUP-GOV-005: no provider role. Drones are platform-owned and registered by admins.
-- Development stage: provider accounts and the drone ownership column are dropped without migration.
ALTER TABLE provider_drone DROP FOREIGN KEY fk_provider_drone_provider;
DROP INDEX idx_provider_drone_catalog ON provider_drone;
ALTER TABLE provider_drone DROP COLUMN provider_id;
DROP TABLE equipment_provider;
CREATE INDEX idx_provider_drone_catalog ON provider_drone(enabled, created_at);
CREATE INDEX idx_provider_drone_airport ON provider_drone(airport_name);
-- Latest order / 执飞中 lookup per drone serial (REQ-SUP-GOV-005 #4/#5).
CREATE INDEX idx_dispatch_order_executed_serial ON dispatch_order(executed_serial_number, created_at);
