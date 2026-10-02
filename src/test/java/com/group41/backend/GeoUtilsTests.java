package com.group41.backend;

import com.group41.backend.activity.service.GeoUtils;
import com.group41.backend.activity.service.GeoUtils.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("Geolocalizacion - distancia y caja delimitadora")
class GeoUtilsTests {

    @Test
    @DisplayName("un grado de longitud en el ecuador mide unos 111.19 km")
    void haversineKnownDistance() {
        assertThat(GeoUtils.distanceKm(0, 0, 0, 1)).isCloseTo(111.19, within(0.05));
        assertThat(GeoUtils.distanceKm(4.6, -74.0, 4.6, -74.0)).isZero();
    }

    @Test
    @DisplayName("la caja contiene un punto a 0.9 km cuando el radio es 1 km")
    void boxContainsPointsInsideRadius() {
        BoundingBox box = GeoUtils.boundingBox(4.6, -74.0, 1.0);

        double northLat = 4.6 + 0.9 / 111.195;
        double eastLon = -74.0 + 0.9 / (111.195 * Math.cos(Math.toRadians(4.6)));

        assertThat(northLat).isBetween(box.minLat(), box.maxLat());
        assertThat(eastLon).isBetween(box.minLon(), box.maxLon());
    }

    @Test
    @DisplayName("lejos del ecuador la caja se ensancha en longitud")
    void boxWidensWithLatitude() {
        BoundingBox box = GeoUtils.boundingBox(60.0, 10.0, 10.0);

        double latSpan = box.maxLat() - box.minLat();
        double lonSpan = box.maxLon() - box.minLon();

        // cos(60 grados) = 0.5, asi que el ancho debe ser el doble del alto.
        assertThat(lonSpan / latSpan).isCloseTo(2.0, within(0.001));
    }
}