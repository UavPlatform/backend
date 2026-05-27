package com.drone.server.calculator;

import com.drone.pojo.entity.RouteWaypoint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class RoutePriceCalculator {

    private static final double EARTH_RADIUS = 6371000.0;

    /**
     * 计算一条航线所有航点连起来的总地面里程
     *
     * @param waypoints 航线中的所有航点集合（前提是已经按执行顺序排好）
     * @return 航线总距离（单位：米）
     */
    public static BigDecimal calculateTotalDistance(List<RouteWaypoint> waypoints) {
        if (waypoints == null || waypoints.size() < 2) {
            return BigDecimal.ZERO;
        }

        double totalDistance = 0.0;

        for (int i = 0; i < waypoints.size() - 1; i++) {
            RouteWaypoint currentPoint = waypoints.get(i);
            RouteWaypoint nextPoint = waypoints.get(i + 1);

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

    /**
     * 基于哈弗辛公式 (Haversine Formula) 计算两个 GPS 坐标点之间的最短球面距离
     */
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
