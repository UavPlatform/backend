-- ============================================================
-- V6: rider_feed 冗余 like_count，支持按热度排序
-- ============================================================

SET @sql_add_lc = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE rider_feed ADD COLUMN like_count INT NOT NULL DEFAULT 0',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider_feed' AND COLUMN_NAME = 'like_count'
);
PREPARE stmt FROM @sql_add_lc; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE rider_feed f
SET like_count = (SELECT COUNT(*) FROM rider_feed_like l WHERE l.feed_id = f.id);

-- 将 role=1 的用户同步到 rider 表
INSERT IGNORE INTO rider (id)
SELECT id FROM user WHERE role = 1;
