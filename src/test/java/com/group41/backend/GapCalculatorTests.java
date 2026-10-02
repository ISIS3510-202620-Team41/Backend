package com.group41.backend;

import com.group41.backend.schedule.service.GapCalculator;
import com.group41.backend.schedule.service.GapCalculator.Interval;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Calculo de huecos libres")
class GapCalculatorTests {

    private static final Instant START = at(6);
    private static final Instant END = at(22);

    private static Instant at(int hour) {
        return Instant.parse("2026-10-02T%02d:00:00Z".formatted(hour));
    }

    private static Interval block(int from, int to) {
        return new Interval(at(from), at(to));
    }

    @Test
    @DisplayName("sin bloques, toda la ventana esta libre")
    void noBlocksMeansWholeWindowIsFree() {
        assertThat(GapCalculator.freeIntervals(START, END, List.of()))
                .containsExactly(new Interval(START, END));
    }

    @Test
    @DisplayName("un bloque en el medio parte la ventana en dos")
    void blockInTheMiddleSplitsWindow() {
        assertThat(GapCalculator.freeIntervals(START, END, List.of(block(10, 12))))
                .containsExactly(block(6, 10), block(12, 22));
    }

    @Test
    @DisplayName("bloques solapados y desordenados se funden")
    void overlappingUnsortedBlocksAreMerged() {
        List<Interval> busy = List.of(block(11, 14), block(8, 12), block(9, 10));

        assertThat(GapCalculator.freeIntervals(START, END, busy))
                .containsExactly(block(6, 8), block(14, 22));
    }

    @Test
    @DisplayName("los bloques que se salen de la ventana se recortan")
    void blocksAreClippedToWindow() {
        List<Interval> busy = List.of(block(4, 8), block(20, 23));

        assertThat(GapCalculator.freeIntervals(START, END, busy))
                .containsExactly(block(8, 20));
    }

    @Test
    @DisplayName("un bloque que cubre toda la ventana no deja huecos")
    void blockCoveringWindowLeavesNoGaps() {
        assertThat(GapCalculator.freeIntervals(START, END, List.of(block(5, 23)))).isEmpty();
    }

    @Test
    @DisplayName("bloques consecutivos no dejan un hueco de cero minutos")
    void adjacentBlocksLeaveNoGapBetween() {
        List<Interval> busy = List.of(block(8, 10), block(10, 12));

        assertThat(GapCalculator.freeIntervals(START, END, busy))
                .containsExactly(block(6, 8), block(12, 22));
    }
}