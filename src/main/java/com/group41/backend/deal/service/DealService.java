package com.group41.backend.deal.service;

import com.group41.backend.deal.controller.DealDtos;
import com.group41.backend.deal.domain.Deal;
import com.group41.backend.deal.domain.DealInteraction;
import com.group41.backend.deal.domain.InteractionType;
import com.group41.backend.deal.repository.DealInteractionRepository;
import com.group41.backend.deal.repository.DealRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** La logica del catalogo de cupones y de su medicion. */
@Service
public class DealService {

    private static final Logger log = LoggerFactory.getLogger(DealService.class);

    /** Alfabeto del codigo: sin I, O, 0 ni 1, que se confunden al leerlos en voz alta. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX", Locale.US);

    private final DealRepository deals;
    private final DealInteractionRepository interactions;

    public DealService(DealRepository deals, DealInteractionRepository interactions) {
        this.deals = deals;
        this.interactions = interactions;
    }

    /** El catalogo vigente. */
    public List<DealDtos.DealResponse> activeCatalog() {
        return deals.findActive(ZonedDateTime.now()).stream().map(DealService::toResponse).toList();
    }

    /**
     * Registra un lote de interacciones.
     *
     * Las filas invalidas se descartan y las demas se guardan. Un cliente que mande un
     * tipo mal escrito no puede hacer que se pierdan las otras nueve interacciones del
     * lote: son datos de medicion, y perder el lote entero sesgaria la BQ mucho mas que
     * perder una fila.
     *
     * @return cuantas se guardaron
     */
    @Transactional
    public int recordInteractions(UUID userId, List<DealDtos.InteractionRequest> batch) {
        ZonedDateTime now = ZonedDateTime.now();
        List<DealInteraction> rows = new ArrayList<>();

        for (DealDtos.InteractionRequest item : batch) {
            InteractionType type;
            try {
                type = InteractionType.valueOf(item.type());
            } catch (IllegalArgumentException ex) {
                log.warn("Tipo de interaccion desconocido: {}", item.type());
                continue;
            }

            // Las redenciones no entran por aqui: tienen su propio endpoint porque generan
            // un codigo y deben ser idempotentes. Aceptarlas en el lote permitiria marcar
            // un cupon como redimido saltandose esa comprobacion.
            if (type == InteractionType.REDEEMED) {
                log.warn("Una redencion debe ir por /redeem, no por el lote de interacciones");
                continue;
            }

            rows.add(new DealInteraction(
                    userId, item.dealId(), type,
                    item.duringBreak(), item.breakMinutesLeft(), null, now));
        }

        interactions.saveAll(rows);
        return rows.size();
    }

    /**
     * Redime un cupon y devuelve el codigo.
     *
     * Es idempotente: si el estudiante ya lo habia redimido, se devuelve el codigo que ya
     * tenia en vez de crear otra redencion. Hace falta porque la app encola las
     * redenciones hechas sin conexion y las reintenta; sin esta comprobacion, un reintento
     * le costaria al comercio un segundo descuento.
     */
    @Transactional
    public DealDtos.RedemptionResponse redeem(UUID userId, UUID dealId,
                                              Boolean duringBreak, Integer breakMinutesLeft) {
        Deal deal = deals.findById(dealId).orElseThrow(() ->
                new IllegalArgumentException("El cupon no existe"));

        if (deal.getValidUntil() != null && deal.getValidUntil().isBefore(ZonedDateTime.now())) {
            throw new IllegalStateException("El cupon ya vencio");
        }

        List<DealInteraction> previous = interactions
                .findByUserIdAndTypeOrderByOccurredAtDesc(userId, InteractionType.REDEEMED)
                .stream()
                .filter(row -> row.getDealId().equals(dealId))
                .toList();

        if (!previous.isEmpty()) {
            DealInteraction existing = previous.get(0);
            return new DealDtos.RedemptionResponse(
                    dealId, existing.getRedemptionCode(), format(existing.getOccurredAt()));
        }

        ZonedDateTime now = ZonedDateTime.now();
        String code = generateCode();

        interactions.save(new DealInteraction(
                userId, dealId, InteractionType.REDEEMED, duringBreak, breakMinutesLeft, code, now));

        return new DealDtos.RedemptionResponse(dealId, code, format(now));
    }

    /**
     * La respuesta a la BQ-11: interacciones y redenciones dentro y fuera de los descansos.
     */
    public DealDtos.BreakFunnelResponse breakFunnel(int days) {
        ZonedDateTime since = ZonedDateTime.now().minusDays(days);

        DealDtos.FunnelGroup inside = DealDtos.FunnelGroup.of(0, 0, 0);
        DealDtos.FunnelGroup outside = DealDtos.FunnelGroup.of(0, 0, 0);

        for (DealInteractionRepository.BreakFunnelRow row : interactions.breakFunnel(since)) {
            DealDtos.FunnelGroup group =
                    DealDtos.FunnelGroup.of(row.getShown(), row.getOpened(), row.getRedeemed());
            if (Boolean.TRUE.equals(row.getDuringBreak())) {
                inside = group;
            } else {
                outside = group;
            }
        }

        List<DealDtos.CategoryGroup> byCategory = interactions.breakFunnelByCategory(since).stream()
                .map(row -> new DealDtos.CategoryGroup(
                        row.getCategory(),
                        row.getShown(),
                        row.getRedeemed(),
                        row.getShown() == 0 ? 0 : (double) row.getRedeemed() / row.getShown()))
                .toList();

        return new DealDtos.BreakFunnelResponse(format(since), inside, outside, byCategory);
    }

    /**
     * Un codigo corto y legible en voz alta.
     *
     * El alfabeto excluye I, O, 0 y 1 porque el estudiante se lo dicta al cajero o lo
     * copia mirando la pantalla, y esos cuatro se confunden entre si.
     */
    private String generateCode() {
        UUID random = UUID.randomUUID();
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        long bits = random.getMostSignificantBits();
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt((int) Math.abs(bits % CODE_ALPHABET.length())));
            bits /= CODE_ALPHABET.length();
            if (bits == 0) {
                bits = random.getLeastSignificantBits();
            }
        }
        return code.toString();
    }

    private static DealDtos.DealResponse toResponse(Deal deal) {
        return new DealDtos.DealResponse(
                deal.getId(),
                deal.getMerchant(),
                deal.getTitle(),
                deal.getDescription(),
                deal.getDiscountPercent(),
                deal.getCategory(),
                deal.getMinutesNeeded(),
                deal.getValidUntil() == null ? null : format(deal.getValidUntil()),
                deal.getImageUrl());
    }

    /** ISO 8601 con offset, el mismo formato que usan los demas endpoints. */
    private static String format(ZonedDateTime value) {
        return value.format(ISO).replace(' ', 'T');
    }
}
