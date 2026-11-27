package com.group41.backend.deal.controller;

import com.group41.backend.deal.service.DealService;
import com.group41.backend.user.domain.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * El catalogo de cupones comerciales y su medicion.
 *
 * Tres endpoints: leer el catalogo, reportar interacciones y redimir. El cuarto, el de
 * estadisticas, es la respuesta de la BQ-11.
 */
@RestController
@RequestMapping("/api/deals")
public class DealController {

    /**
     * Cinco minutos, el mismo valor que usa el catalogo de actividades.
     *
     * Este header es la mitad HTTP del issue #43. Sin el, OkHttp en el telefono no guarda
     * la respuesta: el cliente puede pedir una copia guardada, pero solo hay copia si el
     * servidor dijo que se podia guardar y por cuanto tiempo.
     *
     * Cinco minutos es generoso para un catalogo que cambia cuando un comercio firma un
     * convenio, o sea casi nunca. Lo que acota de verdad la vigencia de un cupon es su
     * propia fecha, que viaja en cada uno.
     */
    private static final String CACHE_HEADER_VALUE = "public, max-age=300";

    /** Ventana por defecto de la consulta de la BQ, y su tope. */
    private static final int DEFAULT_STATS_DAYS = 30;
    private static final int MAX_STATS_DAYS = 365;

    private final DealService dealService;

    public DealController(DealService dealService) {
        this.dealService = dealService;
    }

    /** El catalogo vigente, ordenado por descuento. */
    @GetMapping
    public ResponseEntity<List<DealDtos.DealResponse>> catalog() {
        return ResponseEntity.ok()
                .header("Cache-Control", CACHE_HEADER_VALUE)
                .body(dealService.activeCatalog());
    }

    /**
     * Reporta en lote que unos cupones se mostraron o se abrieron.
     *
     * No lleva header de cache: es una escritura, y cachear una escritura no tiene
     * sentido. Se responde cuantas se guardaron para que el cliente pueda detectar que
     * alguna se descarto por venir mal formada.
     */
    @PostMapping("/interactions")
    public ResponseEntity<Integer> reportInteractions(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody DealDtos.InteractionBatchRequest request) {
        return ResponseEntity.ok(dealService.recordInteractions(user.getId(), request.interactions()));
    }

    /**
     * Redime un cupon y devuelve el codigo para ensenar en el local.
     *
     * El contexto del descanso viaja en el cuerpo porque solo el telefono lo sabe: el
     * horario esta en el servidor, pero quien sabe que hora es para el estudiante y si
     * estaba en un hueco en ese instante es la app. Calcularlo aqui con la hora de
     * llegada seria incorrecto para una redencion hecha sin conexion y enviada horas
     * despues: se registraria el descanso equivocado.
     */
    @PostMapping("/{id}/redeem")
    public ResponseEntity<DealDtos.RedemptionResponse> redeem(
            @AuthenticationPrincipal User user,
            @PathVariable("id") UUID id,
            @RequestBody(required = false) DealDtos.InteractionRequest context) {
        return ResponseEntity.ok(dealService.redeem(
                user.getId(),
                id,
                context == null ? null : context.duringBreak(),
                context == null ? null : context.breakMinutesLeft()));
    }

    /**
     * LA RESPUESTA DE LA BQ-11.
     *
     * Interacciones y redenciones, dentro y fuera de los descansos entre clases, con las
     * tasas ya calculadas y el desglose por categoria de comercio.
     *
     * Las tasas se calculan en el servidor para que todos los que consulten la pregunta
     * vean el mismo numero obtenido de la misma forma, en vez de que cada dashboard
     * divida a su manera.
     */
    @GetMapping("/stats/breaks")
    public ResponseEntity<DealDtos.BreakFunnelResponse> breakStats(
            @RequestParam(value = "days", required = false) Integer days) {
        int window = days == null ? DEFAULT_STATS_DAYS : Math.min(Math.max(days, 1), MAX_STATS_DAYS);
        return ResponseEntity.ok(dealService.breakFunnel(window));
    }
}
