package com.uav.flight.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** OpenAPI illustration only. Null validationVersion asks for the current page; a stale number is rejected. */
@Schema(description = "未冻结对账请求。所有键都必须出现。")
public record EventReconciliationDraftDTO(
        int schemaVersion,
        String chainId,
        Long validationVersion,
        long fromHeight,
        long toHeight,
        Long cursor,
        Long missingCursor,
        int limit,
        Object prefix) { }
