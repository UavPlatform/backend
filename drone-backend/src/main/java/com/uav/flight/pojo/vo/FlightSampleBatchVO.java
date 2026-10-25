package com.uav.flight.pojo.vo;
/** accepted = newly stored; duplicates = already stored (same device + capture time or same sequence, same content);
 *  rejected = invalid or conflicting items that will never be accepted, so the client may drop them from its cache. */
public record FlightSampleBatchVO(int accepted, int duplicates, int rejected) { }
