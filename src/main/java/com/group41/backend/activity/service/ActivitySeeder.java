package com.group41.backend.activity.service;

import com.group41.backend.activity.domain.Activity;
import com.group41.backend.activity.domain.Category;
import com.group41.backend.activity.repository.ActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/** Datos de ejemplo en Bogota para poder probar la busqueda sin crear actividades a mano. */
@Component
@Profile("!test")
public class ActivitySeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ActivitySeeder.class);
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private final ActivityRepository activityRepository;

    public ActivitySeeder(ActivityRepository activityRepository) {
        this.activityRepository = activityRepository;
    }

    @Override
    public void run(String... args) {
        if (activityRepository.existsByEndTimeAfter(ZonedDateTime.now())) {
            return;
        }

        List<Activity> activities = List.of(
                activity("Futbol 5 en la Nacional", "Partido amistoso, llega con ropa deportiva.",
                        Category.DEPORTES, 1, 17, 2, "Canchas Universidad Nacional", 4.6382, -74.0840),
                activity("Trote por el Parque Simon Bolivar", "Vuelta suave de 5 km.",
                        Category.DEPORTES, 2, 6, 2, "Parque Simon Bolivar", 4.6586, -74.0937),
                activity("Grupo de estudio de calculo", "Repaso para el parcial.",
                        Category.ESTUDIO, 1, 14, 3, "Biblioteca Central UN", 4.6360, -74.0830),
                activity("Sesion de estudio en la Luis Angel Arango", "Cada quien con lo suyo, en silencio.",
                        Category.ESTUDIO, 3, 10, 4, "Biblioteca Luis Angel Arango", 4.5968, -74.0728),
                activity("Visita al Museo del Oro", "Recorrido guiado en grupo.",
                        Category.CULTURA, 4, 11, 2, "Museo del Oro", 4.6019, -74.0720),
                activity("Cine foro", "Pelicula y charla despues.",
                        Category.CULTURA, 2, 18, 3, "Teatro Colon", 4.5975, -74.0745),
                activity("Noche de juegos de mesa", "Trae tu juego favorito.",
                        Category.ENTRETENIMIENTO, 3, 19, 3, "Parque de la 93", 4.6764, -74.0485),
                activity("Karaoke", "Para perder la verguenza.",
                        Category.ENTRETENIMIENTO, 5, 20, 3, "Zona T", 4.6670, -74.0525)
        );

        activityRepository.saveAll(activities);
        log.info("Se crearon {} actividades de ejemplo", activities.size());
    }

    private static Activity activity(String title, String description, Category category,
                                     int daysAhead, int hour, int durationHours,
                                     String place, double lat, double lon) {
        ZonedDateTime start = ZonedDateTime.now(BOGOTA)
                .plusDays(daysAhead).withHour(hour).withMinute(0).withSecond(0).withNano(0);
        return new Activity(title, description, category, start, start.plusHours(durationHours),
                place, lat, lon, null);
    }
}