package com.uav.flight.pojo.vo;
/** accepted = newly stored; duplicates = already stored (same connection + sequence, or same device + capture time; first
 *  write wins) and skipped; rejected = invalid or outside the connection window, never accepted, so the client may drop them. */
public record FlightSampleBatchVO(int accepted, int duplicates, int rejected) { }
