package com.hms.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id in the logging MDC for the life of the request and echoes it back, so one
 * request can be traced across gateway, services and the AI service in the logs.
 *
 * <p>An inbound id is honoured - that is what makes a trace span more than one service - but never
 * trusted. It lands in a response header and in every log line for the request, which makes it two
 * injection sinks at once. See {@link #SAFE_ID}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * What an acceptable correlation id looks like: a bounded run of characters that cannot break
     * out of a header or a log line.
     *
     * <p>Replacing a malformed id rather than stripping its bad characters is deliberate. A CR or
     * LF in a response header is response splitting; the same characters in a log line let a caller
     * forge audit entries, and a forged audit trail is worse than a missing one. Stripping would
     * keep some attacker-chosen text in both sinks for no benefit - a caller who sends a malformed
     * id has no legitimate trace to preserve, so they get a fresh one.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = sanitised(request.getHeader(CorrelationId.HEADER));
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    /** The inbound id if it is safe to echo and log, otherwise a fresh one. */
    private static String sanitised(String inbound) {
        if (inbound == null || inbound.isBlank() || !SAFE_ID.matcher(inbound).matches()) {
            return UUID.randomUUID().toString();
        }
        return inbound;
    }
}
