package com.uav.dispatch;
import com.uav.dispatch.service.DispatchGeo;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
class DispatchGeoTest {
    @Test void distanceHandlesRadiusBoundaryAntimeridianAndAntipodalPoints() {
        double degrees = Math.toDegrees(10000.0 / 6371000);
        assertThat(DispatchGeo.distance(0, 0, 0, degrees)).isCloseTo(10000, within(0.00001));
        assertThat(DispatchGeo.distance(0, 179.99, 0, -179.99)).isBetween(2200.0, 2230.0);
        assertThat(DispatchGeo.distance(0, 0, 0, 180)).isFinite();
    }
}
