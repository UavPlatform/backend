-- REQ-SHIP-PUB-002: one partial draft per shipper; no task/order/payment side effects.
CREATE TABLE waybill_draft (
    user_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    schema_version INT NOT NULL,
    step_index INT NOT NULL,
    fields_json LONGTEXT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_waybill_draft_user FOREIGN KEY (user_id) REFERENCES user(id)
);
