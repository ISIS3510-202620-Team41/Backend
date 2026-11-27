package com.group41.backend.deal.controller;

import com.group41.backend.deal.domain.DealCategory;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Lo que viaja entre la app y el backend para los cupones. */
public final class DealDtos {

    private DealDtos() {
    }

    /** Un cupon del catalogo. Fechas en ISO 8601 con offset. */
    public record DealResponse(
            UUID id,
            String merchant,
            String title,
            String description,
            int discountPercent,
            DealCategory category,
            int minutesNeeded,
            String validUntil,
            String imageUrl) {
    }

    /**
     * Una interaccion que la app reporta.
     *
     * `duringBreak` es Boolean y no boolean: tiene que poder llegar nulo cuando el
     * estudiante no tiene horario importado. Un primitivo lo convertiria en false y
     * contaminaria el grupo de control de la BQ-11.
     */
    public record InteractionRequest(
            @NotNull UUID dealId,
            @NotNull String type,
            Boolean duringBreak,
            Integer breakMinutesLeft) {
    }

    /**
     * Varias interacciones de una vez.
     *
     * Se manda en lote porque mostrar una lista de diez cupones produce diez eventos
     * SHOWN. Uno por peticion serian diez peticiones por cada vez que el usuario abre la
     * pantalla, que es exactamente el tipo de cosa que hace que una app se sienta lenta y
     * gaste bateria.
     */
    public record InteractionBatchRequest(@NotNull List<InteractionRequest> interactions) {
    }

    /** Lo que se devuelve al redimir: el codigo que el estudiante ensena en el local. */
    public record RedemptionResponse(UUID dealId, String redemptionCode, String redeemedAt) {
    }

    /**
     * La respuesta a la BQ-11.
     *
     * Las tasas se calculan en el servidor y no en el cliente para que todos los que
     * consulten la pregunta vean el mismo numero, calculado de la misma forma.
     */
    public record BreakFunnelResponse(
            String since,
            FunnelGroup duringBreak,
            FunnelGroup outsideBreak,
            List<CategoryGroup> byCategoryDuringBreak) {
    }

    /** Un grupo del embudo, con sus tasas ya calculadas. */
    public record FunnelGroup(
            long shown,
            long opened,
            long redeemed,
            double interactionRate,
            double redemptionRate) {

        /**
         * Construye el grupo calculando las dos tasas.
         *
         * Con cero mostrados las tasas son cero y no se divide. Devolver cero y no nulo
         * mantiene la forma de la respuesta estable para quien la pinte en el dashboard.
         */
        public static FunnelGroup of(long shown, long opened, long redeemed) {
            double interaction = shown == 0 ? 0 : (double) opened / shown;
            double redemption = shown == 0 ? 0 : (double) redeemed / shown;
            return new FunnelGroup(shown, opened, redeemed, interaction, redemption);
        }
    }

    public record CategoryGroup(DealCategory category, long shown, long redeemed, double redemptionRate) {
    }
}
