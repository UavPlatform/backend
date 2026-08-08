-- ============================================================
-- V8: rider_feed_like / rider_feed_comment 软删除 + 时间戳
-- ============================================================

-- rider_feed_like
SET @sql_add_ld = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE rider_feed_like ADD COLUMN is_deleted TINYINT(1) NOT NULL DEFAULT 0',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider_feed_like' AND COLUMN_NAME = 'is_deleted'
);
PREPARE stmt FROM @sql_add_ld; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_add_lut = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE rider_feed_like ADD COLUMN update_time DATETIME NULL',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider_feed_like' AND COLUMN_NAME = 'update_time'
);
PREPARE stmt FROM @sql_add_lut; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_add_ldt = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE rider_feed_like ADD COLUMN deleted_time DATETIME NULL',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider_feed_like' AND COLUMN_NAME = 'deleted_time'
);
PREPARE stmt FROM @sql_add_ldt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- rider_feed_comment
SET @sql_add_cd = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE rider_feed_comment ADD COLUMN is_deleted TINYINT(1) NOT NULL DEFAULT 0',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider_feed_comment' AND COLUMN_NAME = 'is_deleted'
);
PREPARE stmt FROM @sql_add_cd; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_add_cut = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE rider_feed_comment ADD COLUMN update_time DATETIME NULL',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider_feed_comment' AND COLUMN_NAME = 'update_time'
);
PREPARE stmt FROM @sql_add_cut; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_add_cdt = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE rider_feed_comment ADD COLUMN deleted_time DATETIME NULL',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider_feed_comment' AND COLUMN_NAME = 'deleted_time'
);
PREPARE stmt FROM @sql_add_cdt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
