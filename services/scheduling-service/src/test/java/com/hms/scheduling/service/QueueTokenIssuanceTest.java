package com.hms.scheduling.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.scheduling.domain.QueueToken;
import com.hms.scheduling.repo.QueueTokenRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Token issuance under load, against a real PostgreSQL.
 *
 * <p>This is the test the whole design of {@code queue_counters} exists for. A queue is only worth
 * having if two patients never hold the same number: when 14 is called and two people stand up,
 * neither is wrong and the desk has to sort it out in front of a waiting room. The obvious
 * implementation — read the highest number, add one, insert — is a lost update that produces
 * exactly that, and it produces it rarely enough to survive a manual test and reach a clinic.
 *
 * <p>So the assertion is not "it works", it is "fifty simultaneous check-ins produce fifty distinct
 * consecutive numbers with no gaps", which is false for every read-then-write implementation and
 * true for the single upsert.
 */
@SpringBootTest
@ActiveProfiles("test")
class QueueTokenIssuanceTest {

    private static final int BURST = 50;

    @Autowired
    private QueueTokenRepository tokens;

    @Autowired
    private PlatformTransactionManager transactions;

    /**
     * One transaction per call, which is what fifty concurrent check-in requests really are.
     *
     * <p>Needed rather than incidental: the issuing statement is {@code @Modifying} and so requires
     * a transaction, and running the burst inside a single shared one would serialise it and prove
     * nothing about the race. Each thread therefore commits on its own, exactly as fifty HTTP
     * requests would.
     */
    private int issueInItsOwnTransaction(String room, LocalDate date) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        return template.execute(status -> tokens.issueNextToken(room, date));
    }

    /** Runs {@code task} on {@code n} threads at once and returns once all of them are done. */
    private <T> List<T> inParallel(int n, Callable<T> task) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> futures = pool.invokeAll(java.util.Collections.nCopies(n, task));
            List<T> results = new ArrayList<>(n);
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    /** A room code nothing else in the suite uses, so the counter starts at 1 for this test. */
    private static String freshRoom() {
        return ("Q" + UUID.randomUUID().toString().replace("-", "")).substring(0, 12).toUpperCase(
                java.util.Locale.ROOT);
    }

    @Test
    @DisplayName("fifty simultaneous check-ins get fifty distinct numbers, in one unbroken run")
    void concurrentIssuanceHasNoDuplicatesAndNoGaps() throws Exception {
        String room = freshRoom();
        LocalDate date = LocalDate.of(2026, 3, 12);

        List<Integer> issued = inParallel(BURST, () -> issueInItsOwnTransaction(room, date));

        assertThat(issued)
                .as("no two patients hold the same number")
                .doesNotHaveDuplicates()
                .hasSize(BURST);
        assertThat(issued.stream().sorted().toList())
                .as("and the sequence has no gaps: a missing number is a number nobody answers")
                .isEqualTo(java.util.stream.IntStream.rangeClosed(1, BURST).boxed().toList());
    }

    @Test
    @DisplayName("each room and each day counts on its own")
    void countersAreScopedToARoomAndADay() {
        String roomA = freshRoom();
        String roomB = freshRoom();
        LocalDate monday = LocalDate.of(2026, 3, 9);
        LocalDate tuesday = LocalDate.of(2026, 3, 10);

        // Two clinics running at once are two queues; a shared counter would have the second
        // clinic starting at 38 because the first one was busy.
        assertThat(issueInItsOwnTransaction(roomA, monday)).isEqualTo(1);
        assertThat(issueInItsOwnTransaction(roomB, monday)).isEqualTo(1);
        assertThat(issueInItsOwnTransaction(roomA, monday)).isEqualTo(2);
        // And tomorrow starts again at one, which is what a patient expects of a day's queue.
        assertThat(issueInItsOwnTransaction(roomA, tuesday)).isEqualTo(1);
    }

    @Test
    @DisplayName("a token holds no patient identity at all")
    void aTokenCarriesNothingAboutThePerson() {
        // Structural rather than behavioural, and worth pinning: the corridor display reads these
        // rows, so what makes it safe is that there is nothing in them to leak. If a patient id or
        // an MRN is ever added here, the display's PHI-free rendering stops being a property of
        // the data and starts depending on a query staying careful.
        QueueToken token = new QueueToken(UUID.randomUUID(), "GF-GEN", LocalDate.of(2026, 3, 12), 7);

        assertThat(java.util.Arrays.stream(QueueToken.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .doesNotContain("patientId", "patientMrn", "patientName", "clinicianId");
        assertThat(token.getTokenNumber()).isEqualTo(7);
    }
}
