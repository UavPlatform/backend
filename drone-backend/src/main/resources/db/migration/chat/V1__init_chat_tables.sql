-- ============================================================
-- Chat 模块初始建表（MyBatis-Plus 实体对应，JPA 不管理这些表）
-- Flyway 版本: V1 | 基线版本，含当前完整 schema
-- ============================================================

-- 1. 聊天会话表
CREATE TABLE IF NOT EXISTS `chat_session` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    `name`        VARCHAR(255)          DEFAULT NULL COMMENT '会话名称',
    `type`        TINYINT      NOT NULL              COMMENT '会话类型：0-一对一，1-群组',
    `user_ids`    JSON                  DEFAULT NULL COMMENT '会话成员用户ID列表（JSON数组）',
    `owner_id`    BIGINT       NOT NULL              COMMENT '群主/创建者ID',
    `avatar`      VARCHAR(500)          DEFAULT NULL COMMENT '会话头像URL',
    `description` VARCHAR(500)          DEFAULT NULL COMMENT '会话描述',
    `create_time` BIGINT       NOT NULL              COMMENT '创建时间（毫秒时间戳）',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天会话表';

-- 2. 用户-会话关联表
CREATE TABLE IF NOT EXISTS `chat_user_session` (
    `id`             BIGINT  NOT NULL AUTO_INCREMENT COMMENT '关联ID',
    `session_id`     BIGINT  NOT NULL                COMMENT '会话ID',
    `user_id`        BIGINT  NOT NULL                COMMENT '用户ID',
    `join_time`      BIGINT  NOT NULL                COMMENT '加入时间（毫秒时间戳）',
    `last_read_time` BIGINT           DEFAULT NULL   COMMENT '最后读取消息时间（毫秒时间戳）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_user` (`session_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户-会话关联表';

-- 3. 聊天消息表
CREATE TABLE IF NOT EXISTS `chat_messages` (
    `id`                  BIGINT   NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `msg_id`              VARCHAR(64) NOT NULL            COMMENT '业务唯一UUID',
    `from_user_id`        BIGINT   NOT NULL               COMMENT '发送方用户ID',
    `session_id`          BIGINT   NOT NULL               COMMENT '会话ID',
    `content`             JSON              DEFAULT NULL  COMMENT '消息内容（JSON格式）',
    `status`              TINYINT  NOT NULL DEFAULT 0     COMMENT '消息状态：0-正常，1-已读，2-已撤回，3-发送失败',
    `recall_time`         BIGINT            DEFAULT NULL  COMMENT '撤回时间（毫秒时间戳）',
    `deleted_by_user_ids` JSON              DEFAULT NULL  COMMENT '已删除消息的用户ID列表（JSON数组）',
    `create_time`         BIGINT   NOT NULL               COMMENT '创建时间（毫秒时间戳）',
    `msg_type`            TINYINT  NOT NULL DEFAULT 0     COMMENT '消息类型：0-普通文本',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_id` (`msg_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息表';
