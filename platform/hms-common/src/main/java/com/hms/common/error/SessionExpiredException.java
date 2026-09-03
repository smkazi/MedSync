package com.hms.common.error;

/**
 * A session that was valid has run out of time — idle for too long, or past the absolute lifetime
 * a session is allowed regardless of activity.
 *
 * <p>It exists because neither of the two exceptions this would otherwise be flattened into can
 * say so. {@code GlobalExceptionHandler} turns every {@code BadCredentialsException} into
 * "Invalid username or password" — deliberately, so the login form cannot be used to enumerate
 * accounts — and every other {@code AuthenticationException} into "Authentication failed". Both
 * are the right answers to a caller who has proved nothing. This caller has: they presented a
 * refresh token that this service issued, to an account it knows, and there is nothing left to
 * enumerate. Telling them their password might be wrong sends them to reset a password that is
 * fine.
 *
 * <p>Deliberately not an {@code AuthenticationException}: subclassing one would put it back under
 * the handler that flattens the message, which is the whole thing this class exists to avoid.
 */
public class SessionExpiredException extends RuntimeException {

    public SessionExpiredException(String message) {
        super(message);
    }
}
