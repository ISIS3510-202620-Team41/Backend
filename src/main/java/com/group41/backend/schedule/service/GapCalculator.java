package com.group41.backend.schedule.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resta intervalos ocupados de una ventana y devuelve lo que queda libre. */
public final class GapCalculator {

    private GapCalculator() {
    }

    public record Interval(Instant start, Instant end) {
    }

    /**
     * Recorre los bloques ordenados avanzando un cursor desde el inicio de la
     * ventana: lo que hay entre el cursor y el siguiente bloque es hueco.
     * Los solapes se funden solos porque el cursor solo avanza.
     */
    public static List<Interval> freeIntervals(Instant windowStart, Instant windowEnd, List<Interval> busy) {
        List<Interval> free = new ArrayList<>();
        Instant cursor = windowStart;

        List<Interval> sorted = busy.stream()
                .sorted(Comparator.comparing(Interval::start))
                .toList();

        for (Interval block : sorted) {
            if (!block.end().isAfter(cursor)) {
                continue; // termina antes de donde voy: no cambia nada
            }
            if (!block.start().isBefore(windowEnd)) {
                break; // empieza despues de la ventana, y los siguientes tambien
            }
            if (block.start().isAfter(cursor)) {
                free.add(new Interval(cursor, block.start()));
            }
            cursor = block.end();
            if (!cursor.isBefore(windowEnd)) {
                return free; // el bloque cubre hasta el final de la ventana
            }
        }

        if (cursor.isBefore(windowEnd)) {
            free.add(new Interval(cursor, windowEnd));
        }
        return free;
    }
}