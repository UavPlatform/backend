-- Purge pilot self-owned drone binding; add optional dispatch delivery note.
DROP TABLE IF EXISTS rider_uav;
ALTER TABLE dispatch_order ADD COLUMN delivery_note VARCHAR(500) NULL;
