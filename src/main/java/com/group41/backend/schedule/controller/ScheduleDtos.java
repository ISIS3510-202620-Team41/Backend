package com.group41.backend.schedule.controller;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Contratos de /api/schedules. */
public final class ScheduleDtos {

    private ScheduleDtos() {
    }

    /** Fechas en ISO 8601 con offset, ej. "2026-10-02T06:00:00-05:00". */
    public record FreeInterval(String start, String end) {
    }

    public record GapsResponse(String date, String timezone, List<FreeInterval> free) {
    }

    /**
     * @param imported               bloques de tiempo creados
     * @param skippedEvents          eventos del archivo que no se pudieron procesar
     * @param unsupportedRecurrences eventos de dia completo que se repiten (solo se importa la primera vez)
     */
    public record IcsImportResponse(int imported, int skippedEvents, int unsupportedRecurrences) {
    }

    /** Codigo de autorizacion que entrega la app movil (serverAuthCode). */
    public record GoogleSyncRequest(@NotBlank String authCode) {
    }

    /**
     * @param imported      bloques de tiempo creados
     * @param skippedEvents eventos que no se pudieron interpretar
     * @param calendars     cuantos calendarios de Google se leyeron
     */
    public record GoogleSyncResponse(int imported, int skippedEvents, int calendars) {
    }
}