package com.hms.common.web;

import org.slf4j.MDC;

/** Access to the current request's correlation id, propagated across services via the X-Correlation-Id header. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null ? "none" : value;
    }
}
