package com.uav.dispatch.pojo.vo;

import java.time.LocalDateTime;
import java.util.Map;

public record WaybillDraftVO(int schemaVersion, int step, Map<String, String> fields,
                             long revision, LocalDateTime updatedAt) {
}
