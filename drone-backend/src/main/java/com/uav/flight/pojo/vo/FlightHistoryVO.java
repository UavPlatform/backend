package com.uav.flight.pojo.vo;
import java.util.List;
/** Replay page ordered by device capture time; pass nextCapturedAt/nextSampleId back to continue. */
public record FlightHistoryVO(List<FlightSampleVO> items, List<FlightConnectionVO> connections,
 Long nextCapturedAt, Long nextSampleId, boolean hasNext) { }
