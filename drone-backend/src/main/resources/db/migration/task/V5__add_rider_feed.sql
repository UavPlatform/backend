-- ============================================================
-- V5: 飞手动态（文字+图片/视频 + 点赞 + 评论）
-- ============================================================

-- 1. 动态主表
CREATE TABLE IF NOT EXISTS rider_feed (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT        NOT NULL,
    content     VARCHAR(2000) NOT NULL,
    create_time DATETIME      NOT NULL,
    update_time DATETIME      NULL,
    INDEX idx_feed_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 动态媒体表
CREATE TABLE IF NOT EXISTS rider_feed_media (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    feed_id    BIGINT       NOT NULL,
    media_url  VARCHAR(512) NOT NULL,
    media_type VARCHAR(16)  NOT NULL,
    sort_order INT          NULL,
    INDEX idx_fm_feed_id (feed_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 点赞表（用户+动态唯一约束防重复）
CREATE TABLE IF NOT EXISTS rider_feed_like (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    feed_id     BIGINT   NOT NULL,
    user_id     BIGINT   NOT NULL,
    create_time DATETIME NOT NULL,
    UNIQUE KEY uk_like_feed_user (feed_id, user_id),
    INDEX idx_like_feed (feed_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. 评论表（parent_id 支持回复）
CREATE TABLE IF NOT EXISTS rider_feed_comment (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    feed_id     BIGINT        NOT NULL,
    user_id     BIGINT        NOT NULL,
    content     VARCHAR(1000) NOT NULL,
    parent_id   BIGINT        NULL,
    create_time DATETIME      NOT NULL,
    INDEX idx_comment_feed (feed_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
