package com.uav.server.config;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
/** User confirmed 10km, five-minute position freshness, ten-minute reminder without expansion. */
@Component @Validated @ConfigurationProperties(prefix = "dispatch.nearby")
public class DispatchNearbyConfig {
    @DecimalMin("0.1") @DecimalMax("20000") private double radiusKm = 10;
    @Min(1) @Max(86400) private long locationTtlSeconds = 300;
    @Min(1) @Max(604800) private long reminderSeconds = 600;
    public double getRadiusKm() { return radiusKm; }
    public void setRadiusKm(double value) { radiusKm = value; }
    public long getLocationTtlSeconds() { return locationTtlSeconds; }
    public void setLocationTtlSeconds(long value) { locationTtlSeconds = value; }
    public long getReminderSeconds() { return reminderSeconds; }
    public void setReminderSeconds(long value) { reminderSeconds = value; }
}
