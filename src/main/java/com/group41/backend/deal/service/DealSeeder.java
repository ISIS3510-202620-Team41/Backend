package com.group41.backend.deal.service;

import com.group41.backend.deal.domain.Deal;
import com.group41.backend.deal.domain.DealCategory;
import com.group41.backend.deal.repository.DealRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Cupones de ejemplo de comercios alrededor de los Andes, para poder probar el catalogo
 * sin tener convenios reales.
 *
 * Los minutos de cada uno estan puestos con criterio y no al azar: son el campo que se
 * cruza con los huecos entre clases del estudiante, asi que un cafe tiene que caber en un
 * hueco corto y un almuerzo no. Si todos duraran lo mismo, la BQ-11 no podria distinguir
 * "no le interesaba" de "no le alcanzaba el tiempo", que es media pregunta.
 */
@Component
@Profile("!test")
public class DealSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DealSeeder.class);
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private final DealRepository deals;

    public DealSeeder(DealRepository deals) {
        this.deals = deals;
    }

    @Override
    public void run(String... args) {
        // Igual que el seeder de actividades: si ya hay cupones vigentes no se vuelve a
        // sembrar, para no duplicar el catalogo en cada arranque.
        if (deals.existsByValidUntilAfter(ZonedDateTime.now())) {
            return;
        }

        ZonedDateTime enUnMes = ZonedDateTime.now(BOGOTA).plusMonths(1);

        List<Deal> catalogo = List.of(
                // Cortos: caben en un hueco de media hora entre clases.
                new Deal("Cafe Andino", "20% en cafe caliente", "Valido de lunes a viernes antes de las 11 am.",
                        20, DealCategory.CAFE, 10, enUnMes,
                        "https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?w=800"),
                new Deal("Juan Valdez Centro", "Segundo cafe a mitad de precio", "Presentando carne estudiantil.",
                        50, DealCategory.CAFE, 15, enUnMes,
                        "https://images.unsplash.com/photo-1509042239860-f550ce710b93?w=800"),
                new Deal("Papeleria La 19", "15% en fotocopias e impresiones", "No acumulable con otras promociones.",
                        15, DealCategory.PAPELERIA, 10, enUnMes,
                        "https://images.unsplash.com/photo-1583485088034-697b5bc54ccd?w=800"),

                // Medios: necesitan un hueco de una hora.
                new Deal("Crepes & Waffles La Macarena", "25% en plato fuerte", "De lunes a jueves al almuerzo.",
                        25, DealCategory.COMIDA, 45, enUnMes,
                        "https://images.unsplash.com/photo-1567620905732-2d1ec7ab7445?w=800"),
                new Deal("Wok Zona G", "2x1 en bowls", "Solo para consumo en el local.",
                        50, DealCategory.COMIDA, 50, enUnMes,
                        "https://images.unsplash.com/photo-1512058564366-18510be2db19?w=800"),

                // Largos: no caben en un hueco corto, y eso es justo lo que la BQ mide.
                new Deal("Bodytech Chapinero", "Primer mes al 40%", "Incluye valoracion fisica inicial.",
                        40, DealCategory.DEPORTE, 90, enUnMes,
                        "https://images.unsplash.com/photo-1534438327276-14e5300c3a48?w=800"),
                new Deal("Cine Colombia Avenida Chile", "30% en entrada general", "De domingo a jueves.",
                        30, DealCategory.ENTRETENIMIENTO, 120, enUnMes,
                        "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=800")
        );

        deals.saveAll(catalogo);
        log.info("Catalogo de cupones sembrado: {} cupones", catalogo.size());
    }
}
