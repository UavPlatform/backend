-- 飞手动态：主表 + 媒体 + 点赞 + 评论（列与 Feed / FeedMedia / FeedLike / FeedComment 实体一致，
-- 软删除标记与 like_count 冗余列直接建在初版形态，不再拆分增量迁移）。
--
-- 兼容 MySQL 8.x 与 H2 MySQL 模式（集成测试，R10）：不使用任一侧特有语法
-- （无 ENGINE / CHARSET / 内联 INDEX）；CREATE 为一次性迁移（Flyway 版本化执行）。

-- 动态主表
CREATE TABLE IF NOT EXISTS feed (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    content VARCHAR(2000) NOT NULL,
    like_count INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NULL,
    PRIMARY KEY (id)
);

-- 动态媒体（一条动态多张图/视频）
CREATE TABLE IF NOT EXISTS feed_media (
    id BIGINT NOT NULL AUTO_INCREMENT,
    feed_id BIGINT NOT NULL,
    media_url VARCHAR(512) NOT NULL,
    media_type VARCHAR(16) NOT NULL,
    sort_order INT NULL,
    PRIMARY KEY (id)
);

-- 点赞（同一用户对同一动态仅一条）
CREATE TABLE IF NOT EXISTS feed_like (
    id BIGINT NOT NULL AUTO_INCREMENT,
    feed_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NULL,
    deleted_time TIMESTAMP NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_like_feed_user UNIQUE (feed_id, user_id)
);

-- 评论（parent_id 支持回复）
CREATE TABLE IF NOT EXISTS feed_comment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    feed_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    parent_id BIGINT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NULL,
    deleted_time TIMESTAMP NULL,
    PRIMARY KEY (id)
);

-- 查询索引
CREATE INDEX idx_feed_user ON feed (user_id);
CREATE INDEX idx_fm_feed_id ON feed_media (feed_id);
CREATE INDEX idx_like_feed ON feed_like (feed_id);
CREATE INDEX idx_comment_feed ON feed_comment (feed_id);

-- 存量 role=1 用户补建 rider 行（新注册由 RiderRegisterServiceImpl 直接创建，此处仅补历史数据）
INSERT INTO rider (id, name, age)
SELECT u.id, u.user_name, 0 FROM user u
WHERE u.role = 1 AND u.id NOT IN (SELECT r.id FROM rider r);
