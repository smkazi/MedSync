package com.hms.scheduling.web.dto;

import com.hms.scheduling.domain.SchedulingEnums;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class QueueDtos {

    private QueueDtos() {
    }

    /** One number on the staff board. */
    public record QueueEntry(int tokenNumber, SchedulingEnums.TokenStatus status,
                             Instant issuedAt, Instant calledAt, UUID appointmentId) {
    }

    /**
     * The queue as the desk sees it.
     *
     * <p>Carries the appointment id per row, which is the one thing the public view does not: the
     * desk needs to get from "number 14 has not answered" to a patient in one click.
     */
    public record QueueBoard(String roomCode, LocalDate serviceDate, Integer nowServing,
                             List<QueueEntry> tokens) {

        public QueueBoard {
            tokens = tokens == null ? List.of() : List.copyOf(tokens);
        }
    }

    /**
     * The queue as a corridor sees it.
     *
     * <p>A separate record rather than a filtered {@link QueueBoard}, and that is the security
     * decision in this file. This screen is served without a token to anybody who asks, and is
     * visible to every visitor and passer-by in the building — so the type itself has nowhere to
     * put a name, an MRN, an appointment id or a patient id. A filtered view of the staff board
     * would be one field away from leaking, and that field gets added by somebody adding a
     * feature who never sees this comment.
     *
     * <p>Even the number of people waiting is absent: "you are fourteenth" plus a visible arrival
     * order is enough for a stranger to work out who is who.
     *
     * @param nowServing the number being called, or null before the clinic has started
     * @param upcoming   the next few waiting numbers, in order
     */
    public record PublicQueueBoard(String roomCode, Integer nowServing, List<Integer> upcoming) {

        public PublicQueueBoard {
            upcoming = upcoming == null ? List.of() : List.copyOf(upcoming);
        }
    }
}
