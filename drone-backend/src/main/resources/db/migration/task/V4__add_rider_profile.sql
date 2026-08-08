-- 1. 飞手资料表：安全加字段
SET @sql_add_cert = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE rider ADD COLUMN cert_number VARCHAR(64) NULL',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider' AND COLUMN_NAME = 'cert_number'
);
PREPARE stmt FROM @sql_add_cert; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_add_from = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE rider ADD COLUMN cert_valid_from DATE NULL',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider' AND COLUMN_NAME = 'cert_valid_from'
);
PREPARE stmt FROM @sql_add_from; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_add_until = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE rider ADD COLUMN cert_valid_until DATE NULL',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider' AND COLUMN_NAME = 'cert_valid_until'
);
PREPARE stmt FROM @sql_add_until; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_add_age = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE rider ADD COLUMN age INT NULL',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rider' AND COLUMN_NAME = 'age'
);
PREPARE stmt FROM @sql_add_age; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 上传文件表状态列改为 ENUM
ALTER TABLE uploaded_file
    MODIFY COLUMN upload_status ENUM('PENDING_SIGN','COMPLETED','FAILED') NOT NULL DEFAULT 'PENDING_SIGN';

-- 3. 无人机驾驶资质表
CREATE TABLE IF NOT EXISTS rider_aircraft_qualification (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id  BIGINT       NOT NULL,
    category VARCHAR(32)  NOT NULL,
    license  VARCHAR(32)  NOT NULL,
    weight   VARCHAR(32)  NOT NULL,
    INDEX idx_qual_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. 性能索引（安全创建）
SET @sql_idx_role = (
    SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_user_role ON user (`role`)',
        'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND INDEX_NAME = 'idx_user_role'
);
PREPARE stmt FROM @sql_idx_role; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_idx_rc = (
    SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_assign_rider_complete ON task_assignment (rider_id, complete_time)',
        'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task_assignment' AND INDEX_NAME = 'idx_assign_rider_complete'
);
PREPARE stmt FROM @sql_idx_rc; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_idx_ra = (
    SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_assign_rider_accept ON task_assignment (rider_id, accept_time)',
        'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task_assignment' AND INDEX_NAME = 'idx_assign_rider_accept'
);
PREPARE stmt FROM @sql_idx_ra; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_idx_task = (
    SELECT IF(COUNT(*) = 0,
        'CREATE INDEX idx_assign_task ON task_assignment (task_id)',
        'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'task_assignment' AND INDEX_NAME = 'idx_assign_task'
);
PREPARE stmt FROM @sql_idx_task; EXECUTE stmt; DEALLOCATE PREPARE stmt;
