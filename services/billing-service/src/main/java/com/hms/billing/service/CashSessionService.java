package com.hms.billing.service;

import com.hms.billing.domain.BillingEnums;
import com.hms.billing.domain.CashSession;
import com.hms.billing.domain.Money;
import com.hms.billing.repo.CashSessionRepository;
import com.hms.billing.repo.RefundRepository;
import com.hms.billing.web.dto.BillingDtos;
import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening a drawer, counting it, and signing it off.
 *
 * <p>The day book was readable and unsigned: it totalled what a day billed and collected and split
 * collections by method, and nothing in the platform ever said "this is what was in the drawer, and
 * here is who counted it". A shift is the unit rather than a day, because a drawer is handed over
 * between people and a day's takings cannot say which of three cashiers is short two hundred.
 *
 * <p>Only cash is counted. Card and UPI settle into the acquirer's batch and cannot be short by an
 * error of counting; asking somebody to declare them would invite them to type the expected figure
 * back in and call it reconciled. They are reported beside the count so a cashier can tick them
 * against the terminal's own batch, which is a different act and belongs on the same paper.
 */
@Service
public class CashSessionService {

    private final CashSessionRepository sessions;
    private final RefundRepository refunds;
    private final AuditService audit;

    public CashSessionService(CashSessionRepository sessions, RefundRepository refunds,
                              AuditService audit) {
        this.sessions = sessions;
        this.refunds = refunds;
        this.audit = audit;
    }

    /**
     * Opens a drawer for the caller.
     *
     * <p>One at a time, enforced by a partial unique index rather than by the check below. The
     * check is there to produce a sentence instead of a constraint violation; the index is there
     * because two browser tabs both pass a check.
     */
    @Transactional
    public BillingDtos.CashSessionResponse open(BillingDtos.OpenCashSessionRequest request) {
        String cashier = CurrentUser.usernameOrSystem();
        sessions.findByCashierAndStatus(cashier, BillingEnums.CashSessionStatus.OPEN)
                .ifPresent(open -> {
                    throw new ConflictException(("%s already has a drawer open, started %s. Close "
                            + "that one before opening another — two open drawers cannot both be "
                            + "the one a payment goes into.")
                            .formatted(cashier, open.getOpenedAt()));
                });

        CashSession session = new CashSession(cashier, request.openingFloat());
        try {
            sessions.save(session);
            sessions.flush();
        } catch (DataIntegrityViolationException ex) {
            // The index caught what the check above raced past.
            throw new ConflictException(
                    "%s already has a drawer open. Close it before opening another.".formatted(cashier));
        }
        audit.record("CASH_SESSION_OPENED", "CashSession", session.getId(),
                "%s opened with a float of %s".formatted(cashier, session.getOpeningFloat()));
        return toResponse(session);
    }

    /**
     * Counts the drawer and signs the shift off.
     *
     * <p>The expected figure is computed here and never accepted from the caller: a cash-up whose
     * difference is supplied by the person being reconciled is not a control. A variance must be
     * explained in writing, which the database also insists on — the whole value of the count is
     * that somebody accounts for the difference while they still remember the shift.
     *
     * <p>An administrator may close somebody else's abandoned drawer, and the row records both
     * names, because "who counted this" and "whose drawer was it" stop being the same question the
     * moment that happens.
     */
    @Transactional
    public BillingDtos.CashSessionResponse close(UUID id, BillingDtos.CloseCashSessionRequest request) {
        CashSession session = require(id);
        if (!session.isOpen()) {
            throw new ConflictException(("This drawer was already closed %s by %s. A shift is "
                    + "counted once; a correction to it is a note, not a second count.")
                    .formatted(session.getClosedAt(), session.getClosedBy()));
        }

        String actor = CurrentUser.usernameOrSystem();
        BigDecimal expected = expectedCash(session);
        BigDecimal declared = Money.scale(request.declaredCash());
        String notes = request.notes() == null ? null : request.notes().trim();

        if (declared.compareTo(expected) != 0 && (notes == null || notes.isBlank())) {
            throw new BadRequestException(("The drawer counts %s and the platform expects %s, a "
                    + "difference of %s. Say what accounts for it — an unexplained variance is a "
                    + "number nobody will investigate.")
                    .formatted(declared, expected, Money.scale(declared.subtract(expected))));
        }

        session.close(declared, expected, actor, notes);
        sessions.save(session);

        audit.record("CASH_SESSION_CLOSED", "CashSession", session.getId(),
                "%s counted %s against %s expected (%s %s)".formatted(actor, declared, expected,
                        session.getVariance().abs(), session.varianceDescription()));
        return toResponse(session);
    }

    /** The caller's own open drawer, with what it should hold right now. */
    @Transactional(readOnly = true)
    public Optional<BillingDtos.CashSessionResponse> current() {
        return sessions.findByCashierAndStatus(CurrentUser.usernameOrSystem(),
                        BillingEnums.CashSessionStatus.OPEN)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public BillingDtos.CashSessionResponse get(UUID id) {
        return toResponse(require(id));
    }

    /**
     * The register of shifts.
     *
     * <p>A cashier sees their own and an administrator sees everybody's. Not a privacy rule — a
     * variance is nobody's private business — but a useful one: a list of every cashier's shifts is
     * noise to somebody looking for their own last count, and the person who reconciles across
     * cashiers is the one who holds ADMIN.
     */
    @Transactional(readOnly = true)
    public Page<BillingDtos.CashSessionResponse> list(boolean everyone, Pageable pageable) {
        Page<CashSession> page = everyone
                ? sessions.findAllByOrderByOpenedAtDesc(pageable)
                : sessions.findByCashierOrderByOpenedAtDesc(CurrentUser.usernameOrSystem(), pageable);
        return page.map(this::toResponse);
    }

    /**
     * Attributes money to the caller's open drawer, or to none.
     *
     * <p>Called on the payment and refund paths, and deliberately returning null rather than
     * throwing when no drawer is open: money is never refused for want of an open shift. A hospital
     * takes cash at a counter whether or not somebody remembered the ceremony, and a platform that
     * refused it would be teaching people to work around the cash-up on their first busy morning.
     */
    @Transactional(readOnly = true)
    public UUID currentSessionId() {
        return sessions.findByCashierAndStatus(CurrentUser.usernameOrSystem(),
                        BillingEnums.CashSessionStatus.OPEN)
                .map(CashSession::getId)
                .orElse(null);
    }

    /** Float, plus cash in, less cash out — what the drawer should hold. */
    private BigDecimal expectedCash(CashSession session) {
        return Money.scale(session.getOpeningFloat().add(sessions.netCashIn(session.getId())));
    }

    private CashSession require(UUID id) {
        return sessions.findById(id)
                .orElseThrow(() -> NotFoundException.of("CashSession", id));
    }

    private BillingDtos.CashSessionResponse toResponse(CashSession session) {
        List<BillingDtos.MethodTotal> taken = sessions.paymentTotalsByMethod(session.getId()).stream()
                .map(RefundRepository.MethodTotalRow::of)
                .map(row -> new BillingDtos.MethodTotal(row.method(), Money.scale(row.amount()),
                        row.count()))
                .toList();
        List<BillingDtos.MethodTotal> paidBack = sessions.refundTotalsByMethod(session.getId()).stream()
                .map(RefundRepository.MethodTotalRow::of)
                .map(row -> new BillingDtos.MethodTotal(row.method(), Money.scale(row.amount()),
                        row.count()))
                .toList();

        // An open drawer shows what it should hold now; a closed one shows what it held when it was
        // counted. Recomputing the closed figure would let a later correction move a number
        // somebody has already signed against.
        BigDecimal expected = session.isOpen() ? expectedCash(session) : session.getExpectedCash();

        return new BillingDtos.CashSessionResponse(session.getId(), session.getCashier(),
                session.getStatus(), session.getOpenedAt(), session.getOpeningFloat(),
                session.getClosedAt(), session.getClosedBy(), session.getDeclaredCash(),
                expected, session.getVariance(), session.varianceDescription(),
                session.getNotes(), taken, paidBack);
    }
}
