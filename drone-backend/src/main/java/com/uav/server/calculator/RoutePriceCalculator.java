package com.uav.server.calculator;

import com.uav.task.pojo.entity.TaskWaypoint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class RoutePriceCalculator {

    private static final double EARTH_RADIUS = 6371000.0;

    public static BigDecimal calculateTotalDistance(List<TaskWaypoint> waypoints) {
        if (waypoints == null || waypoints.size() < 2) {
            return BigDecimal.ZERO;
        }

        double totalDistance = 0.0;

        for (int i = 0; i < waypoints.size() - 1; i++) {
            TaskWaypoint currentPoint = waypoints.get(i);
            TaskWaypoint nextPoint = waypoints.get(i + 1);

            double lat1 = currentPoint.getLatitude();
            double lon1 = currentPoint.getLongitude();
            double lat2 = nextPoint.getLatitude();
            double lon2 = nextPoint.getLongitude();

            totalDistance += getDistance2D(lat1, lon1, lat2, lon2);
        }

        return BigDecimal.valueOf(totalDistance).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculatePrice(BigDecimal distanceMeters, BigDecimal pricePerMeter) {
        return pricePerMeter.multiply(distanceMeters).setScale(2, RoundingMode.HALF_UP);
    }

    private static double getDistance2D(double lat1, double lon1, double lat2, double lon2) {
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);

        double a = radLat1 - radLat2;
        double b = Math.toRadians(lon1) - Math.toRadians(lon2);

        double calc = Math.pow(Math.sin(a / 2), 2)
                + Math.cos(radLat1) * Math.cos(radLat2)
                * Math.pow(Math.sin(b / 2), 2);

        return 2 * Math.asin(Math.sqrt(calc)) * EARTH_RADIUS;
    }
}
