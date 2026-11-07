package com.uav.flight.pojo.vo;

/** Inclusive absent heights. A missing height is not a permanent event rejection. */
public record MissingRangeVO(long fromHeight, long toHeight) { }
