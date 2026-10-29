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
                        Category.DEPORTES, 1, 17, 1, "Canchas Universidad Nacional", 4.6382, -74.0840,
                        "https://images.unsplash.com/photo-1579952363873-27f3bade9f55?w=800", "$15.000", 10,
                        "Canchas sintéticas con iluminación nocturna, vestieres y graderías."),
                activity("Trote por el Parque Simon Bolivar", "Vuelta suave de 5 km.",
                        Category.DEPORTES, 2, 6, 1, "Parque Simon Bolivar", 4.6586, -74.0937,
                        "https://images.unsplash.com/photo-1476480862126-209bfaa8edc8?w=800", "Gratis", 25,
                        "Ruta pavimentada alrededor del lago, con estaciones de hidratación y zona verde."),
                activity("Grupo de estudio de calculo", "Repaso para el parcial.",
                        Category.ESTUDIO, 1, 14, 3, "Biblioteca Central UN", 4.6360, -74.0830,
                        "https://images.unsplash.com/photo-1434030216411-0b793f4b4173?w=800", "Gratis", 8,
                        "Sala de estudio grupal en el 2do piso, cuenta con tablero acrílico y tomas eléctricas."),
                activity("Sesion de estudio en la Luis Angel Arango", "Cada quien con lo suyo, en silencio.",
                        Category.ESTUDIO, 3, 10, 4, "Biblioteca Luis Angel Arango", 4.5968, -74.0728,
                        "https://images.unsplash.com/photo-1521587760476-6c12a4b040da?w=800", "Gratis", 15,
                        "Piso 3, zona de silencio absoluto. Excelente luz natural e internet de alta velocidad."),
                activity("Visita al Museo del Oro", "Recorrido guiado en grupo.",
                        Category.CULTURA, 4, 11, 2, "Museo del Oro", 4.6019, -74.0720,
                        "https://images.unsplash.com/photo-1566127444979-b3d2b654e3d7?w=800", "$8.000", 20,
                        "Salas de exhibición prehispánica con aire acondicionado y audioguías disponibles."),
                activity("Cine foro", "Pelicula y charla despues.",
                        Category.CULTURA, 2, 18, 3, "Teatro Colon", 4.5975, -74.0745,
                        "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=800", "Gratis", 30,
                        "Auditorio cerrado con proyector HD, sonido envolvente y silletería tipo teatro."),
                activity("Noche de juegos de mesa", "Trae tu juego favorito.",
                        Category.ENTRETENIMIENTO, 3, 19, 3, "Parque de la 93", 4.6764, -74.0485,
                        "https://images.unsplash.com/photo-1610890716171-6b1bb98ffd09?w=800", "$12.000", 12,
                        "Zona al aire libre con mesas de madera bajo sombrilla y cerca a cafeterías."),
                activity("Karaoke", "Para perder la verguenza.",
                        Category.ENTRETENIMIENTO, 5, 20, 3, "Zona T", 4.6670, -74.0525,
                        "https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=800", "$15.000", 15,
                        "Salón privado insonorizado con dos micrófonos y pantalla para letras.")
        );

        activityRepository.saveAll(activities);
        log.info("Se crearon {} actividades de ejemplo", activities.size());
    }

    private static Activity activity(String title, String description, Category category,
                                     int daysAhead, int hour, int durationHours,
                                     String place, double lat, double lon,
                                     String imageUrl, String price, Integer capacity, String aboutLocation) {
        ZonedDateTime start = ZonedDateTime.now(BOGOTA)
                .plusDays(daysAhead).withHour(hour).withMinute(0).withSecond(0).withNano(0);
        return new Activity(title, description, category, start, start.plusHours(durationHours),
                place, lat, lon, null, imageUrl, price, capacity, aboutLocation);
    }
}
