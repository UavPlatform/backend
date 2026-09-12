-- ============================================================
-- 测试专用 chat 模块建表（H2 MySQL 兼容模式）
-- 与 db/migration/chat/V1__init_chat_tables.sql 同构：
-- JSON 列在 H2 中以 TEXT 承载（JacksonTypeHandler 仍按字符串序列化）。
-- JPA 管理的表由 hibernate ddl-auto=create-drop 生成，脚本随后执行。
-- ============================================================

CREATE TABLE IF NOT EXISTS chat_session (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(255) DEFAULT NULL,
    type        TINYINT      NOT NULL,
    user_ids    TEXT         DEFAULT NULL,
    owner_id    BIGINT       NOT NULL,
    avatar      VARCHAR(500) DEFAULT NULL,
    description VARCHAR(500) DEFAULT NULL,
    create_time BIGINT       NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS chat_user_session (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    session_id     BIGINT NOT NULL,
    user_id        BIGINT NOT NULL,
    join_time      BIGINT NOT NULL,
    last_read_time BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_session_user UNIQUE (session_id, user_id)
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    msg_id              VARCHAR(64) NOT NULL,
    from_user_id        BIGINT      NOT NULL,
    session_id          BIGINT      NOT NULL,
    content             TEXT        DEFAULT NULL,
    status              TINYINT     NOT NULL DEFAULT 0,
    recall_time         BIGINT      DEFAULT NULL,
    deleted_by_user_ids TEXT        DEFAULT NULL,
    create_time         BIGINT      NOT NULL,
    msg_type            TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_msg_id UNIQUE (msg_id)
);
