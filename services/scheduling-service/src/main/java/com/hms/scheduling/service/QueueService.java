package com.hms.scheduling.service;

import com.hms.common.audit.AuditService;
import com.hms.scheduling.domain.Appointment;
import com.hms.scheduling.domain.QueueToken;
import com.hms.scheduling.domain.SchedulingEnums;
import com.hms.scheduling.repo.QueueTokenRepository;
import com.hms.scheduling.web.dto.QueueDtos;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outpatient token queue.
 *
 * <p>A number is issued when a patient checks in and called when their consultation begins, so the
 * queue is a by-product of the appointment lifecycle rather than a thing anybody maintains. That
 * matters: a queue somebody has to keep in step with the appointment book is a queue that drifts
 * out of step with it by the middle of the morning.
 *
 * <p>Issuing runs inside the check-in transaction on purpose. If the check-in rolls back the token
 * must not exist — a number handed to nobody is a gap in the sequence, and a called number that
 * nobody answers is exactly what makes a corridor lose faith in the board.
 */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    private final QueueTokenRepository tokens;
    private final AuditService audit;
    private final ZoneId clinicZone;

    public QueueService(QueueTokenRepository tokens, AuditService audit,
                        @Value("${hms.scheduling.zone:Asia/Kolkata}") String timezone) {
        this.tokens = tokens;
        this.audit = audit;
        // The clinic's own zone, not the server's, and the same property SlotCalculator reads.
        // A service date is a working day at a physical desk: a clinic in UTC+05:30 running an
        // evening list is already past UTC midnight, and a server rolling the date over there
        // would restart the numbering halfway through the queue.
        this.clinicZone = ZoneId.of(timezone);
    }

    /**
     * Issues the number for a check-in, or returns the one already issued.
     *
     * <p>Returns empty when the appointment has no room. A token queue is a queue for a door: it
     * is what a corridor display shows and what a patient looks up at. An appointment with no room
     * yet — booked before the clinic knew where it would run — checks in without a number rather
     * than being given one for a queue that has no location, and the board simply does not list it.
     */
    @Transactional
    public Optional<QueueToken> issueFor(Appointment appointment) {
        Optional<QueueToken> existing = tokens.findByAppointmentId(appointment.getId());
        if (existing.isPresent()) {
            return existing;
        }
        String roomCode = appointment.getRoomCode();
        if (roomCode == null || roomCode.isBlank()) {
            log.debug("Appointment {} has no room, so no queue token", appointment.getId());
            return Optional.empty();
        }
        LocalDate serviceDate = appointment.getStartsAt().atZone(clinicZone).toLocalDate();
        int number = tokens.issueNextToken(roomCode, serviceDate);

        QueueToken token = tokens.save(new QueueToken(appointment.getId(), roomCode, serviceDate, number));
        // The audit detail carries the room and the number and no patient identity, which is the
        // same rule the board and the display follow. AuditService's own contract forbids clinical
        // free text in `detail`; a token is not clinical, but a queue position beside an MRN is
        // still a statement about a person's day.
        audit.record("QUEUE_TOKEN_ISSUED", "QueueToken", token.getId(),
                "%s #%d on %s".formatted(roomCode, number, serviceDate));
        return Optional.of(token);
    }

    /** Marks the number as called, when the consultation begins. */
    @Transactional
    public void markCalled(UUID appointmentId) {
        tokens.findByAppointmentId(appointmentId).ifPresent(QueueToken::call);
    }

    /** Takes the number off the board, when the consultation ends or the patient never came. */
    @Transactional
    public void markFinished(UUID appointmentId) {
        tokens.findByAppointmentId(appointmentId).ifPresent(QueueToken::finish);
    }

    /**
     * The board for a room, for staff.
     *
     * <p>Carries the appointment id, so the desk can get from a number to a patient in one click.
     * That is the difference between this and the public view, and it is the only difference.
     */
    @Transactional(readOnly = true)
    public QueueDtos.QueueBoard board(String roomCode, LocalDate date) {
        LocalDate serviceDate = date == null ? today() : date;
        List<QueueToken> issued = tokens.findByRoomCodeAndServiceDateOrderByTokenNumberAsc(
                roomCode, serviceDate);
        return new QueueDtos.QueueBoard(roomCode, serviceDate,
                nowServing(issued).orElse(null),
                issued.stream()
                        .map(token -> new QueueDtos.QueueEntry(token.getTokenNumber(), token.getStatus(),
                                token.getIssuedAt(), token.getCalledAt(), token.getAppointmentId()))
                        .toList());
    }

    /**
     * The corridor display.
     *
     * <p><strong>Numbers only.</strong> The room's code, the number being served, and the next few
     * waiting. No name, no MRN, no appointment id, no clinician, no department, no count of how
     * many people are waiting — that last one because "you are fourteenth" plus a visible arrival
     * order is enough for a stranger to work out who is who.
     *
     * <p>This screen is visible to every visitor, delivery driver and passer-by in the building,
     * and it is reachable without a token, so it is the one response in the platform where the
     * safest possible content is the correct content. It is built from a different DTO rather than
     * a filtered version of the staff board, because a filtered view is one field away from
     * leaking and the field gets added by somebody adding a feature.
     */
    @Transactional(readOnly = true)
    public QueueDtos.PublicQueueBoard publicBoard(String roomCode) {
        LocalDate serviceDate = today();
        List<QueueToken> issued = tokens.findByRoomCodeAndServiceDateOrderByTokenNumberAsc(
                roomCode, serviceDate);
        List<Integer> waiting = issued.stream()
                .filter(token -> token.getStatus() == SchedulingEnums.TokenStatus.WAITING)
                .map(QueueToken::getTokenNumber)
                .sorted()
                .limit(UPCOMING)
                .toList();
        return new QueueDtos.PublicQueueBoard(roomCode, nowServing(issued).orElse(null), waiting);
    }

    /** How many numbers ahead the display shows. Enough to be useful, few enough to be a queue. */
    private static final int UPCOMING = 5;

    /**
     * The number currently being served: the highest one that has been called and not finished.
     *
     * <p>Highest rather than earliest, because a clinician who calls 15 while 14 is still marked
     * called — a patient who stepped out — is serving 15, and the corridor needs to agree with the
     * room.
     */
    private static Optional<Integer> nowServing(List<QueueToken> issued) {
        return issued.stream()
                .filter(token -> token.getStatus() == SchedulingEnums.TokenStatus.CALLED)
                .max(Comparator.comparingInt(QueueToken::getTokenNumber))
                .map(QueueToken::getTokenNumber);
    }

    private LocalDate today() {
        return LocalDate.now(Clock.system(clinicZone));
    }
}
