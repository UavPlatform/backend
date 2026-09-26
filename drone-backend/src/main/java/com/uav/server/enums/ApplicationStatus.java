package com.uav.server.enums;

import lombok.Getter;

/**
 * 应征记录撮合子状态（ADR-0003 决定 6「状态表达」的独立表表达）。
 *
 * <p>本任务（TASK-BACKEND-003）只写入 {@link #ACTIVE}；{@link #SELECTED} 与 {@link #CLOSED}
 * 为 TASK-BACKEND-004 的用户选定（select-rider）预留：用户选定一条应征后该条置
 * {@code SELECTED}，其余自动置 {@code CLOSED}（ADR-0003：用户选定后其余应征自动失效）。
 */
@Getter
public enum ApplicationStatus {

    /** 应征中：报价有效，等待用户选定。 */
    ACTIVE("应征中"),

    /** 已选定：用户已选定该飞手下单（TASK-BACKEND-004 语义）。 */
    SELECTED("已选定"),

    /** 已失效：同任务其它应征被选定后自动关闭（TASK-BACKEND-004 语义）。 */
    CLOSED("已失效");

    private final String description;

    ApplicationStatus(String description) {
        this.description = description;
    }
}
