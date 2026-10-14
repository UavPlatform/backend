-- Existing rows are untrusted until checked against object storage.
ALTER TABLE task_attachment ADD COLUMN upload_status VARCHAR(16) NOT NULL DEFAULT 'LEGACY';
-- New rows, including writes from older servers, must not become visible automatically.
ALTER TABLE task_attachment ALTER COLUMN upload_status SET DEFAULT 'PENDING';
