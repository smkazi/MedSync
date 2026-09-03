package com.hms.common.error;

/**
 * A refusal the caller is entitled to understand.
 *
 * <p>{@code GlobalExceptionHandler} flattens Spring's {@code AccessDeniedException} to "You do not
 * have permission to perform this action", and that is right for what raises it: a
 * {@code @PreAuthorize} refusal is a role failure, and telling a lab technician exactly which role
 * they are missing on which resource narrates the platform's authorisation model to somebody who
 * has just been refused by it.
 *
 * <p>Some refusals are the opposite. When a control exists precisely so the caller can be told what
 * to do instead — a clinician who is not on a patient's care team and may open the chart by
 * recording why — a message saying nothing turns a working control into an apparent outage, and the
 * clinician telephones somebody rather than using the mechanism built for them. This is for those:
 * raised deliberately, by our own code, when the answer is more useful than the silence.
 *
 * <p>Deliberately not a subclass of {@code AccessDeniedException}: extending it would put this
 * straight back under the handler that flattens the message.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
