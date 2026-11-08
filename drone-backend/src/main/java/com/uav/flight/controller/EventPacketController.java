package com.uav.flight.controller;

import com.uav.flight.event.EventChainLookupService;
import com.uav.flight.event.EventFormatException;
import com.uav.flight.event.EventLimits;
import com.uav.flight.event.EventPacketService;
import com.uav.flight.event.EventReconciliationService;
import com.uav.flight.event.UploadOutcome;
import com.uav.flight.pojo.dto.EventPacketDraftDTO;
import com.uav.flight.pojo.dto.EventReconciliationDraftDTO;
import com.uav.flight.pojo.vo.EventChainRefVO;
import com.uav.flight.pojo.vo.EventPacketAckVO;
import com.uav.flight.pojo.vo.EventReconciliationVO;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.annotation.RequireRole;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Draft PR1. Event packets do not require a flight connection, active serial, or a live session.
 * Settlement, projection and deletion of the legacy live protocol are later phases.
 * The request body is the original bytes; Jackson does not bind it.
 */
@RestController
@RequestMapping("/dispatch")
public class EventPacketController {
    private final EventPacketService packets;
    private final EventReconciliationService reconciliation;
    private final EventChainLookupService lookup;

    public EventPacketController(EventPacketService packets, EventReconciliationService reconciliation,
            EventChainLookupService lookup) {
        this.packets = packets;
        this.reconciliation = reconciliation;
        this.lookup = lookup;
    }

    @PostMapping("/pilot/orders/{orderId}/event-packets")
    @RequireRole(1)
    @RateLimiter(limit = EventLimits.RATE_LIMIT, windowSeconds = EventLimits.RATE_WINDOW_SECONDS)
    @Operation(summary = "上传事件原包（草案，未冻结）", description = "严格 UTF-8 与整数剖面解析后自行重算链式哈希。同 packetId 同规范内容返回最初 receipt，ACCEPTED 不会变成 DUPLICATE。同 ID 异内容 409 并保留冲突证据。格式、schema 或自哈希错误不落完整确认。401/408/429/5xx 可带原包重试；429 带 Retry-After。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = EventPacketDraftDTO.class)))
    public Result<EventPacketAckVO> upload(@PathVariable String orderId, HttpServletRequest request) {
        var outcome = packets.upload(UserContext.getUserId(), orderId, body(request));
        if (outcome instanceof UploadOutcome.Rejected rejected) {
            throw new BusinessException(rejected.status(), rejected.code(), rejected.message());
        }
        return Result.success(((UploadOutcome.Stored) outcome).ack());
    }

    @PostMapping("/pilot/orders/{orderId}/event-reconciliation")
    @RequireRole(1)
    @RateLimiter(limit = EventLimits.RATE_LIMIT, windowSeconds = EventLimits.RATE_WINDOW_SECONDS)
    @Operation(summary = "对账事件链（草案，未冻结）", description = "按固定 validationVersion 返回区间内的高度哈希和有界缺洞。版本已变化时返回 RECONCILIATION_VERSION_CHANGED，不返回残页。正常路径直接给出区间，不必每次二分。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = EventReconciliationDraftDTO.class)))
    public Result<EventReconciliationVO> reconcile(@PathVariable String orderId, HttpServletRequest request) {
        return Result.success(reconciliation.reconcile(UserContext.getUserId(), orderId, body(request)));
    }

    /**
     * Chain discovery for a terminal that lost its local journal. Chain identity includes recording_source_id, which a
     * reinstall mints anew, so without this the client cannot find the chain its own order and airframe already own.
     * Read-only; returns no packet bodies.
     */
    @GetMapping("/pilot/orders/{orderId}/event-chains")
    @RequireRole(1)
    @RateLimiter(limit = EventLimits.RATE_LIMIT, windowSeconds = EventLimits.RATE_WINDOW_SECONDS)
    @Operation(summary = "查询订单与机身已绑定的事件链（草案，未冻结）",
            description = "按 orderId + 机身身份（serialNumber/serialSource/modelCode）列出已存在的链，与 recordingSourceId 无关，供重装或换机后续链。terminalMismatch=true 表示该链由其它 recordingSourceId 记录。只读，不返回包体。")
    public Result<List<EventChainRefVO>> chains(
            @PathVariable String orderId,
            @RequestParam("serialNumber") String serialNumber,
            @RequestParam("serialSource") String serialSource,
            @RequestParam("modelCode") String modelCode,
            @RequestParam("recordingSourceId") String recordingSourceId) {
        return Result.success(lookup.chainsFor(UserContext.getUserId(), orderId,
                serialNumber, serialSource, modelCode, recordingSourceId));
    }

    private static byte[] body(HttpServletRequest request) {
        requireJson(request);
        try (var input = request.getInputStream()) {
            var out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > EventLimits.MAX_BODY_BYTES) throw malformed("LIMIT");
                out.write(buffer, 0, read);
            }
            if (total == 0) throw malformed("SYNTAX");
            return out.toByteArray();
        } catch (IOException e) {
            throw malformed("SYNTAX");
        }
    }

    private static void requireJson(HttpServletRequest request) {
        try {
            var media = MediaType.parseMediaType(request.getContentType() == null ? "" : request.getContentType());
            if (!"application".equalsIgnoreCase(media.getType()) || !"json".equalsIgnoreCase(media.getSubtype())) {
                throw malformed("SYNTAX");
            }
            if (media.getCharset() != null && !StandardCharsets.UTF_8.name().equalsIgnoreCase(media.getCharset().name())) {
                throw malformed("UTF8");
            }
        } catch (InvalidMediaTypeException e) {
            throw malformed("SYNTAX");
        }
    }

    private static BusinessException malformed(String token) {
        return new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.EVENT_PACKET_MALFORMED, token);
    }
}
