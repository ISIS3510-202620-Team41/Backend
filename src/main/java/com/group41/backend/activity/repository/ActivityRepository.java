package com.group41.backend.activity.repository;

import com.group41.backend.activity.domain.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public interface ActivityRepository extends JpaRepository<Activity, UUID> {

    /** Actividades dentro de la caja y que aun no terminan. La distancia exacta se refina en Java. */
    @Query("""
            select a from Activity a
            where a.latitude between :minLat and :maxLat
              and a.longitude between :minLon and :maxLon
              and a.endTime > :now
            order by a.startTime
            """)
    List<Activity> findInBox(@Param("minLat") double minLat,
                             @Param("maxLat") double maxLat,
                             @Param("minLon") double minLon,
                             @Param("maxLon") double maxLon,
                             @Param("now") ZonedDateTime now);

    boolean existsByEndTimeAfter(ZonedDateTime now);
}