package com.uav.dispatch.service;
/** Same-coordinate-system spherical distance, never a flight route or GPS verification. */
public final class DispatchGeo {
    private DispatchGeo() { }
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double a = Math.pow(Math.sin(Math.toRadians(lat2 - lat1) / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(Math.toRadians(lng2 - lng1) / 2), 2);
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, a))));
    }
    static Double pickupDistance(com.uav.dispatch.pojo.entity.DispatchOrder order, double lat, double lng) {
        try {
            var fields = DispatchOrderViews.fields(order);
            double x = Double.parseDouble(fields.get("start.lat")), y = Double.parseDouble(fields.get("start.lng"));
            if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 90 || Math.abs(y) > 180) return null;
            return distance(lat, lng, x, y);
        } catch (RuntimeException invalid) { return null; }
    }
}
