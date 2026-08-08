-- ============================================================
-- V7: rider 表添加 create_time / update_time
-- ============================================================

SET @sql_add_ct = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE rider ADD COLUMN create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider' AND COLUMN_NAME = 'create_time'
);
PREPARE stmt FROM @sql_add_ct; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_add_ut = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE rider ADD COLUMN update_time DATETIME NULL',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider' AND COLUMN_NAME = 'update_time'
);
PREPARE stmt FROM @sql_add_ut; EXECUTE stmt; DEALLOCATE PREPARE stmt;
