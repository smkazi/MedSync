package com.hms.common.error;

/**
 * A dependency this request needed could not be reached, and nothing was done as a result.
 *
 * <p><strong>503 and not 500, and the difference is what the caller should do next.</strong> A 500
 * says the platform is broken and there is nothing to try; a 503 says the request was refused
 * intact, no partial work was left behind, and the same request will succeed once the dependency is
 * back. On this platform that distinction is load-bearing exactly once so far: the public-health
 * line list registers a disclosure before it produces a file, so an unreachable disclosure register
 * means no file was produced — an operator who is told "try again" retries, and an operator who is
 * told the platform is broken telephones somebody and eventually sends the list another way.
 *
 * <p>The message is <strong>kept</strong>, for the reason {@link ForbiddenException} gives about
 * its own: a refusal a person is expected to act on has to say what happened. It must therefore
 * name the dependency and the consequence and nothing else — never a URL, a stack frame or a
 * clinical value.
 *
 * <p>The clients that fail <em>closed</em> without this — refusing a due list because the patient
 * directory is unreachable, refusing a chart because the care team cannot be read — throw
 * {@code AccessDeniedException} or {@code IllegalStateException} and are deliberately left alone:
 * they are answering "you may not", not "come back later". Reach for this one only where retrying
 * unchanged is the actual remedy.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
