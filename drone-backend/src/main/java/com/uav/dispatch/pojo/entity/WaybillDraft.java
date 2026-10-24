package com.uav.dispatch.pojo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "waybill_draft")
@Getter
@Setter
public class WaybillDraft {
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Version
    @Column(name = "revision", nullable = false)
    private Long revision;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "step_index", nullable = false)
    private int step;

    @Column(name = "fields_json", nullable = false, columnDefinition = "LONGTEXT")
    private String fieldsJson;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
