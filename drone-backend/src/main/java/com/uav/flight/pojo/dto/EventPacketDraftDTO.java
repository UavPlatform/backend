package com.uav.flight.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OpenAPI illustration only. The server does not bind this type.
 * Bytes are parsed by the strict integer-profile parser. See spec/event-contract/DRAFT.md.
 */
@Schema(description = "未冻结事件包。缺字段不等于 null。整数不得用小数、指数或前导零。UUID 与哈希必须小写。")
public record EventPacketDraftDTO(
        @Schema(description = "必须为 2") int schemaVersion,
        @Schema(description = "必须为 2") int hashVersion,
        String orderId,
        String recordingSourceId,
        String chainId,
        @Schema(description = "原样机身号，不大小写折叠") String serialNumber,
        @Schema(description = "序列号来源令牌。不同令牌不是同一机身，服务端不把飞控 SN 与产品 SN 视为相同") String serialSource,
        @Schema(description = "稳定机型码。展示名不能出现在包里") String modelCode,
        String packetId,
        @Schema(description = "首包必须是 null，且字段必须存在") String prevPacketId,
        @Schema(description = "从 1 起") long height,
        String prevChainHash,
        String chainHash,
        EventDraftDTO[] events) {
    @Schema(description = "单个事件。未知字段在包根和事件封套上会拒绝整包")
    public record EventDraftDTO(String eventId, @Schema(description = "epoch 毫秒") long capturedAt, String type, Object payload) { }
}
