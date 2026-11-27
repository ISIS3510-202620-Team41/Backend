package com.group41.backend.deal.repository;

import com.group41.backend.deal.domain.DealInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public interface DealInteractionRepository extends JpaRepository<DealInteraction, UUID> {

    /**
     * LA CONSULTA DE LA BQ-11.
     *
     * Devuelve, por cada contexto (en descanso / fuera de descanso), cuantos cupones se
     * mostraron, cuantos se abrieron y cuantos se redimieron. Con esos tres numeros se
     * calculan las dos tasas que pide la pregunta.
     *
     * Tres decisiones que la hacen correcta:
     *
     * 1. `d.duringBreak is not null` excluye a los estudiantes que no han importado su
     *    horario. Incluirlos como "fuera de descanso" diluiria el grupo de control y la
     *    comparacion diria menos de lo que parece.
     *
     * 2. Se agrupa por `duringBreak`, no se devuelven dos consultas sueltas. Asi los
     *    numeros de los dos grupos vienen del mismo instante y las tasas son comparables.
     *
     * 3. Se cuenta con `sum(case when ...)` en lugar de tres consultas con `where type =`.
     *    Es un solo recorrido de la tabla en vez de tres, y sobre todo garantiza que los
     *    tres conteos corresponden exactamente al mismo conjunto de filas.
     */
    @Query("""
            select d.duringBreak as duringBreak,
                   sum(case when d.type = com.group41.backend.deal.domain.InteractionType.SHOWN then 1 else 0 end) as shown,
                   sum(case when d.type = com.group41.backend.deal.domain.InteractionType.OPENED then 1 else 0 end) as opened,
                   sum(case when d.type = com.group41.backend.deal.domain.InteractionType.REDEEMED then 1 else 0 end) as redeemed
            from DealInteraction d
            where d.duringBreak is not null
              and d.occurredAt >= :since
            group by d.duringBreak
            """)
    List<BreakFunnelRow> breakFunnel(@Param("since") ZonedDateTime since);

    /**
     * Lo mismo, pero por categoria de comercio y solo dentro de los descansos.
     *
     * Responde la pregunta de seguimiento que el equipo comercial haria enseguida: si los
     * descansos funcionan, que tipo de comercio aprovecha mas ese momento.
     */
    @Query("""
            select dl.category as category,
                   sum(case when d.type = com.group41.backend.deal.domain.InteractionType.SHOWN then 1 else 0 end) as shown,
                   sum(case when d.type = com.group41.backend.deal.domain.InteractionType.REDEEMED then 1 else 0 end) as redeemed
            from DealInteraction d join Deal dl on dl.id = d.dealId
            where d.duringBreak = true
              and d.occurredAt >= :since
            group by dl.category
            """)
    List<CategoryFunnelRow> breakFunnelByCategory(@Param("since") ZonedDateTime since);

    /** El historial de redenciones de un estudiante, de la mas reciente a la mas antigua. */
    List<DealInteraction> findByUserIdAndTypeOrderByOccurredAtDesc(
            UUID userId, com.group41.backend.deal.domain.InteractionType type);

    /**
     * Si este estudiante ya redimio este cupon.
     *
     * Hace la redencion idempotente: reintentar desde la cola sin conexion no puede
     * generar dos redenciones del mismo cupon, que para el comercio serian dos descuentos.
     */
    boolean existsByUserIdAndDealIdAndType(
            UUID userId, UUID dealId, com.group41.backend.deal.domain.InteractionType type);

    /** Una fila del embudo, agrupada por si fue en descanso o no. */
    interface BreakFunnelRow {
        Boolean getDuringBreak();

        long getShown();

        long getOpened();

        long getRedeemed();
    }

    /** Una fila del embudo dentro de los descansos, por categoria de comercio. */
    interface CategoryFunnelRow {
        com.group41.backend.deal.domain.DealCategory getCategory();

        long getShown();

        long getRedeemed();
    }
}
