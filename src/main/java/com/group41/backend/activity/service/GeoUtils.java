package com.group41.backend.activity.service;

/** Calculos de distancia y caja delimitadora sobre la esfera terrestre. */
public final class GeoUtils {

    public static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoUtils() {
    }

    public record BoundingBox(double minLat, double maxLat, double minLon, double maxLon) {
    }

    /** Distancia de gran circulo (Haversine) en kilometros. */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * Caja que contiene el circulo de ese radio. Un grado de longitud mide menos
     * cuanto mas lejos del ecuador, por eso el ancho se corrige con cos(latitud).
     */
    public static BoundingBox boundingBox(double lat, double lon, double radiusKm) {
        double dLat = Math.toDegrees(radiusKm / EARTH_RADIUS_KM);
        double cos = Math.max(Math.cos(Math.toRadians(lat)), 0.01);
        double dLon = Math.toDegrees(radiusKm / (EARTH_RADIUS_KM * cos));
        return new BoundingBox(
                Math.max(-90.0, lat - dLat),
                Math.min(90.0, lat + dLat),
                lon - dLon,
                lon + dLon);
    }
}