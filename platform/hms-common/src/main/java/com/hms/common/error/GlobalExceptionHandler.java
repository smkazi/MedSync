package com.hms.common.error;

import com.hms.common.api.ApiError;
import com.hms.common.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns exceptions into {@link ApiError} bodies so no stack trace ever reaches a client. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), req);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), req);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> badRequest(BadRequestException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), req);
    }

    /**
     * Our own 403, with its message kept. See {@link ForbiddenException} for when that is right and
     * when the flattened one below is.
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> forbiddenWithReason(ForbiddenException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "Forbidden", "You do not have permission to perform this action", req);
    }

    /**
     * A dependency was unreachable and the request was refused whole. See
     * {@link ServiceUnavailableException} for why this is not the 500 it would otherwise be.
     *
     * <p>{@code Retry-After} is deliberately absent: the platform has no idea when the dependency
     * comes back, and a number invented to fill a header is worse than no header, because a client
     * will obey it.
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiError> unavailable(ServiceUnavailableException ex, HttpServletRequest req) {
        log.warn("Dependency unavailable on {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", ex.getMessage(), req);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> badCredentials(BadCredentialsException ex, HttpServletRequest req) {
        // Deliberately vague: the caller must not learn whether the username exists.
        return build(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid username or password", req);
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ApiError> locked(LockedException ex, HttpServletRequest req) {
        return build(HttpStatus.LOCKED, "Locked", ex.getMessage(), req);
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiError> disabled(DisabledException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), req);
    }

    /**
     * A session that timed out, answered as itself rather than as a credential failure. The caller
     * held a token this service issued, so there is no account to enumerate and no reason to send
     * them to reset a password that is not the problem.
     */
    @ExceptionHandler(SessionExpiredException.class)
    public ResponseEntity<ApiError> sessionExpired(SessionExpiredException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, "Session Expired", ex.getMessage(), req);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> authentication(AuthenticationException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication failed", req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST.value(), "Validation Failed",
                "One or more fields are invalid", req.getRequestURI(), CorrelationId.current())
                .withFieldErrors(fieldErrors);
        // Content type stated for the reason {@link #build} records: without it, a caller that did
        // not ask for JSON gets an empty 500 in place of the field errors.
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException ex, HttpServletRequest req) {
        // An unmapped path is a 404, not a server fault - and must not be logged as one.
        return build(HttpStatus.NOT_FOUND, "Not Found", "No endpoint matches " + req.getRequestURI(), req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException ex, HttpServletRequest req) {
        // A body Jackson cannot map is the caller's mistake. The parser message is not echoed back:
        // it can quote the payload, which for this platform may be patient data.
        log.debug("Unreadable request body on {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "Bad Request",
                "Request body is missing, malformed, or has a field of the wrong type", req);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> badParameter(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Bad Request", "A request parameter is missing or of the wrong type",
                req);
    }

    /**
     * Wrong verb on a real path. Left unmapped this is a 500, which is both wrong and noisy: a
     * scanner walking the API with the wrong methods fills the logs with server errors and buries
     * the failures that matter.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                     HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                // RFC 9110 requires Allow on a 405, and it is the one thing that makes the error
                // actionable.
                .header("Allow", String.join(", ",
                        ex.getSupportedMethods() == null ? new String[0] : ex.getSupportedMethods()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(error(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed",
                        ex.getMethod() + " is not supported on this resource", req));
    }

    /** A body in a format nothing here reads. 415, not 500. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> unsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                         HttpServletRequest req) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type",
                "This endpoint accepts application/json", req);
    }

    /** An Accept header nothing here can satisfy. */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> notAcceptable(HttpMediaTypeNotAcceptableException ex,
                                                  HttpServletRequest req) {
        return build(HttpStatus.NOT_ACCEPTABLE, "Not Acceptable",
                "This endpoint produces application/json", req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Data integrity violation on {}: {}", req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "Conflict",
                "The request conflicts with existing data or a database constraint", req);
    }

    /**
     * A lost race on a versioned row is the caller's to retry, not a server fault. Mapped
     * explicitly because the default is a 500, and a 500 says "we are broken" when the honest
     * answer is "someone else changed this row while you were editing it".
     *
     * <p>Reaching this handler on a path where concurrency is expected rather than exceptional is
     * a design smell, not a resolved problem: the login bookkeeping used to land here on every
     * simultaneous sign-in, and the fix was to stop taking the lock, not to translate the failure.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> optimisticLock(OptimisticLockingFailureException ex, HttpServletRequest req) {
        log.warn("Optimistic locking failure on {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, "Conflict",
                "This record was changed by someone else while you were working on it. Reload and try again", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}", req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred. Reference correlation id " + CorrelationId.current(), req);
    }

    /**
     * Builds the error response, stating its own content type rather than letting the request's
     * {@code Accept} header choose one.
     *
     * <p>That explicit content type is a fix, not a formality. Spring skips content negotiation when
     * a {@code ResponseEntity} already carries a concrete {@code Content-Type}; without one, an
     * error on an endpoint whose caller asked for something other than JSON has no acceptable
     * representation, and the attempt to write it fails <em>during</em> writing — which the
     * container turns into a bare <strong>500 with an empty body</strong>. Any caller asking
     * strictly for the media type an endpoint serves was therefore given a 500 in place of every
     * 400, 404 and 409 the platform meant to send.
     *
     * <p>Found by hand against the live stack while adding the wristband: {@code Accept:
     * image/svg+xml} against an archived patient answered 500 and nothing else. It had been true of
     * the specimen label since labels were built, and nothing caught it because a browser sends
     * {@code text/html,…} with a wildcard and the test suites send one too — every caller the suites
     * had was a caller that happened to accept JSON as well.
     *
     * <p>An error is not a negotiable representation. A caller that cannot parse the reason is still
     * better served by the reason than by an empty 500.
     */
    private ResponseEntity<ApiError> build(HttpStatus status, String title, String detail, HttpServletRequest req) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(error(status, title, detail, req));
    }

    private ApiError error(HttpStatus status, String title, String detail, HttpServletRequest req) {
        return ApiError.of(status.value(), title, detail, req.getRequestURI(), CorrelationId.current());
    }
}
