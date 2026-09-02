package com.hms.scheduling.web;

import com.hms.common.security.Roles;
import com.hms.scheduling.service.QueueService;
import com.hms.scheduling.web.dto.QueueDtos;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The token queue: one endpoint for the desk and one for the corridor.
 *
 * <p>Two endpoints rather than one with a flag, because they are not the same resource. The staff
 * board is patient-linked data behind a role; the display is a public sign. Collapsing them would
 * mean the difference between them was a query parameter, and a query parameter is a thing a
 * caller supplies.
 */
@RestController
public class QueueController {

    private final QueueService queue;

    public QueueController(QueueService queue) {
        this.queue = queue;
    }

    /**
     * The board for a room, for staff.
     *
     * <p>{@code CLINICAL_READ} rather than {@code FRONT_DESK}: the nurse calling the next patient
     * and the clinician wondering how far behind they are both need this, and it carries a room
     * code, some numbers and an appointment id — less than the appointment book they can already
     * read.
     */
    @GetMapping("/queue/{roomCode}")
    @PreAuthorize(Roles.CLINICAL_READ)
    public QueueDtos.QueueBoard board(
            @PathVariable String roomCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return queue.board(roomCode.trim().toUpperCase(java.util.Locale.ROOT), date);
    }

    /**
     * The corridor display. <strong>Unauthenticated, and PHI-free by construction.</strong>
     *
     * <p>Allowlisted through {@code hms.security.public-paths}, which exists for this and had no
     * user until now. What comes back is a room code, the number being served and the next few
     * waiting — there is no name, MRN, appointment id or patient id in
     * {@link QueueDtos.PublicQueueBoard} to return.
     *
     * <p>No date parameter, deliberately. A wall display shows today; accepting a date would let
     * anybody on the internet read the shape of any past clinic, which is a small disclosure made
     * for no reason at all — nobody standing in a corridor wants last Tuesday.
     *
     * <p>Cached for ten seconds. A display polls, and this is the one endpoint where a cache header
     * is doing real work: it keeps a screen refreshing every two seconds from turning into a
     * database query every two seconds, and ten seconds of staleness on a number being called is
     * invisible to somebody sitting down.
     */
    @GetMapping("/public/queue/{roomCode}")
    public ResponseEntity<QueueDtos.PublicQueueBoard> display(@PathVariable String roomCode) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(10)).cachePublic())
                .body(queue.publicBoard(roomCode.trim().toUpperCase(java.util.Locale.ROOT)));
    }
}
