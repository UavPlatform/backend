-- ADR-0001 baseline: 全部表结构由 Flyway 管理，JPA ddl-auto=validate
-- 兼容 MySQL 8.x 与 H2 MySQL 模式（集成测试）

CREATE TABLE IF NOT EXISTS user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_name VARCHAR(255),
    password VARCHAR(255),
    status INT,
    role INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_name UNIQUE (user_name)
);

CREATE TABLE IF NOT EXISTS user_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_name VARCHAR(255),
    dji_Id VARCHAR(255),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS rider (
    id BIGINT NOT NULL,
    name VARCHAR(255),
    age INT NOT NULL,
    location VARCHAR(255),
    self_introduction VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS admin (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    phone_number VARCHAR(255),
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_admin_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS uav (
    id BIGINT NOT NULL AUTO_INCREMENT,
    uav_name VARCHAR(255),
    online_status CHAR(1),
    uav_create_time TIMESTAMP,
    update_time TIMESTAMP,
    dji_id VARCHAR(255),
    controller_model VARCHAR(255),
    is_available CHAR(1),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS uav_gps_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    uav_id BIGINT,
    device_id VARCHAR(64),
    order_num VARCHAR(64),
    longitude DOUBLE NOT NULL,
    latitude DOUBLE NOT NULL,
    altitude DOUBLE,
    speed DOUBLE,
    battery INT,
    flight_status INT,
    operation VARCHAR(64),
    timestamp BIGINT NOT NULL,
    received_at BIGINT NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_gps_order_num ON uav_gps_record (order_num);
CREATE INDEX idx_gps_uav_ts ON uav_gps_record (uav_id, timestamp);
CREATE INDEX idx_gps_received_at ON uav_gps_record (received_at);

CREATE TABLE IF NOT EXISTS task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_num VARCHAR(64),
    task_name VARCHAR(255),
    user_id BIGINT,
    task_type VARCHAR(32),
    task_status VARCHAR(32),
    default_speed DOUBLE,
    default_height DOUBLE,
    description VARCHAR(255),
    task_time TIMESTAMP,
    reward DOUBLE,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_task_num UNIQUE (task_num)
);

CREATE TABLE IF NOT EXISTS route_waypoint (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    waypoint_order INT NOT NULL,
    longitude DOUBLE NOT NULL,
    latitude DOUBLE NOT NULL,
    altitude DOUBLE,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS task_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    rider_id BIGINT NOT NULL,
    accept_time TIMESTAMP NOT NULL,
    complete_time TIMESTAMP,
    complete_note VARCHAR(500),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS task_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_num VARCHAR(64) NOT NULL,
    uploader_id BIGINT NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_task_attachment_object_key UNIQUE (object_key)
);
CREATE INDEX idx_task_attachment_task_num ON task_attachment (task_num);

CREATE TABLE IF NOT EXISTS mission_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_num VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    total_amount DECIMAL(10, 2) NOT NULL,
    total_distance DECIMAL(10, 2) NOT NULL,
    order_status VARCHAR(32) NOT NULL,
    pending_key VARCHAR(64),
    create_time TIMESTAMP NOT NULL,
    version BIGINT,
    update_time TIMESTAMP,
    executed_at TIMESTAMP,
    execute_result VARCHAR(32),
    PRIMARY KEY (id),
    CONSTRAINT uk_mission_order_num UNIQUE (order_num),
    CONSTRAINT uk_mission_order_pending_key UNIQUE (pending_key)
);
CREATE INDEX idx_user_status ON mission_order (user_id, order_status);

CREATE TABLE IF NOT EXISTS order_review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_num VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    rating INT NOT NULL,
    content VARCHAR(512),
    create_time TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_review_order_num UNIQUE (order_num)
);

CREATE TABLE IF NOT EXISTS order_complaint (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_num VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    reason VARCHAR(32) NOT NULL,
    description VARCHAR(1024),
    status VARCHAR(16) NOT NULL,
    admin_note VARCHAR(512),
    refund_amount DECIMAL(10, 2),
    create_time TIMESTAMP NOT NULL,
    resolve_time TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_complaint_order_num UNIQUE (order_num)
);

CREATE TABLE IF NOT EXISTS pay_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_num VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    pay_channel VARCHAR(32),
    prepay_id VARCHAR(64),
    transaction_id VARCHAR(64),
    status VARCHAR(32),
    error_msg VARCHAR(512),
    callback_body TEXT,
    create_time TIMESTAMP NOT NULL,
    pay_time TIMESTAMP,
    update_time TIMESTAMP,
    version INT,
    PRIMARY KEY (id)
);
CREATE INDEX idx_pay_record_order_num ON pay_record (order_num);
CREATE INDEX idx_pay_record_user_id ON pay_record (user_id);
CREATE INDEX idx_transaction_id ON pay_record (transaction_id);
CREATE INDEX idx_status ON pay_record (status);
CREATE INDEX idx_create_time ON pay_record (create_time);

CREATE TABLE IF NOT EXISTS uploaded_file (
    id BIGINT NOT NULL AUTO_INCREMENT,
    upload_id VARCHAR(64) NOT NULL,
    original_name VARCHAR(256) NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    file_url VARCHAR(512),
    file_size BIGINT NOT NULL,
    mime_type VARCHAR(128),
    file_suffix VARCHAR(16),
    upload_status VARCHAR(32) NOT NULL,
    order_num VARCHAR(64),
    user_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_upload_id UNIQUE (upload_id)
);
CREATE INDEX idx_user_id ON uploaded_file (user_id);
CREATE INDEX idx_order_num ON uploaded_file (order_num);

CREATE TABLE IF NOT EXISTS rider_uav (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    dji_id VARCHAR(64) NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_rider_uav_user FOREIGN KEY (user_id) REFERENCES user (id)
);

CREATE TABLE IF NOT EXISTS chat_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255),
    type INT NOT NULL,
    user_ids JSON,
    owner_id BIGINT NOT NULL,
    avatar VARCHAR(500),
    description VARCHAR(500),
    create_time BIGINT NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS chat_user_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    join_time BIGINT NOT NULL,
    last_read_time BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_session_user UNIQUE (session_id, user_id)
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    msg_id VARCHAR(64) NOT NULL,
    from_user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    content TEXT,
    status INT NOT NULL DEFAULT 0,
    recall_time BIGINT,
    deleted_by_user_ids JSON,
    create_time BIGINT NOT NULL,
    msg_type INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_msg_id UNIQUE (msg_id)
);
