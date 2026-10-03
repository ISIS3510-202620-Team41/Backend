package com.group41.backend.schedule.controller;

import com.group41.backend.schedule.service.GoogleCalendarService;
import com.group41.backend.schedule.service.ScheduleService;
import com.group41.backend.user.domain.User;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final GoogleCalendarService googleCalendarService;

    public ScheduleController(ScheduleService scheduleService, GoogleCalendarService googleCalendarService) {
        this.scheduleService = scheduleService;
        this.googleCalendarService = googleCalendarService;
    }

    /** Intervalos libres del usuario autenticado en un dia. tz es opcional (por defecto Bogota). */
    @GetMapping("/me/gaps")
    public ResponseEntity<ScheduleDtos.GapsResponse> myGaps(
            @AuthenticationPrincipal User user,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(value = "tz", required = false) String tz) {
        return ResponseEntity.ok(scheduleService.gapsFor(user.getId(), date, tz));
    }

    @GetMapping("/google/status")
    public ResponseEntity<ScheduleDtos.GoogleStatusResponse> googleStatus(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(googleCalendarService.status(user.getId()));
    }

    /** Importa un .ics y reemplaza los bloques ICS anteriores del usuario. */
    @PostMapping("/sync/ics")
    public ResponseEntity<ScheduleDtos.IcsImportResponse> syncIcs(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "tz", required = false) String tz) {
        return ResponseEntity.ok(scheduleService.importIcs(user.getId(), file, tz));
    }

    /** Conecta Google Calendar con el codigo del movil y reemplaza los bloques de Google del usuario. */
    @PostMapping("/sync/google")
    public ResponseEntity<ScheduleDtos.GoogleSyncResponse> syncGoogle(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody ScheduleDtos.GoogleSyncRequest request,
            @RequestParam(value = "tz", required = false) String tz) {
        return ResponseEntity.ok(googleCalendarService.connect(user.getId(), request.authCode(), tz));
    }

    /** Resincroniza Google Calendar con el token ya guardado, sin pedirle nada al movil. */
    @PostMapping("/sync/google/refresh")
    public ResponseEntity<ScheduleDtos.GoogleSyncResponse> refreshGoogle(
            @AuthenticationPrincipal User user,
            @RequestParam(value = "tz", required = false) String tz) {
        return ResponseEntity.ok(googleCalendarService.resync(user.getId(), tz));
    }
}