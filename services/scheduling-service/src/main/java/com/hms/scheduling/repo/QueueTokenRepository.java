package com.hms.scheduling.repo;

import com.hms.scheduling.domain.QueueToken;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QueueTokenRepository extends JpaRepository<QueueToken, UUID> {

    Optional<QueueToken> findByAppointmentId(UUID appointmentId);

    List<QueueToken> findByRoomCodeAndServiceDateOrderByTokenNumberAsc(String roomCode,
                                                                       LocalDate serviceDate);

    /**
     * Issues the next token for a room on a day, atomically.
     *
     * <p>One statement, and that is the whole point. {@code SELECT max(token_number) + 1} followed
     * by an insert is a lost update: two receptionists checking patients in at the same instant
     * both read 13 and both write 14, and then two people stand up when 14 is called. The unique
     * index on (room, date, number) would catch it as a 500 rather than a duplicate, which is
     * better than a duplicate and still a failed check-in.
     *
     * <p>Native SQL because {@code ON CONFLICT ... RETURNING} has no JPQL equivalent — this is
     * PostgreSQL's upsert and the returning clause is what makes the read and the write one thing.
     * The insert path leaves {@code next_token} at 2 and returns 1; the conflict path increments
     * and returns what was there. The same shape as {@code UserRepository.recordFailedLogin}, for
     * the same reason.
     *
     * <p>Declared as a query rather than {@code @Modifying}, which is the one non-obvious part.
     * {@code @Modifying} runs the statement through {@code executeUpdate}, and PostgreSQL answers
     * {@code A result was returned when none was expected} — the returning clause is exactly what
     * makes this not a plain update. It still requires a transaction, which the caller supplies.
     */
    @Query(value = """
            INSERT INTO queue_counters (room_code, service_date, next_token)
            VALUES (:roomCode, :serviceDate, 2)
            ON CONFLICT (room_code, service_date)
            DO UPDATE SET next_token = queue_counters.next_token + 1
            RETURNING next_token - 1
            """, nativeQuery = true)
    int issueNextToken(@Param("roomCode") String roomCode,
                       @Param("serviceDate") LocalDate serviceDate);
}
