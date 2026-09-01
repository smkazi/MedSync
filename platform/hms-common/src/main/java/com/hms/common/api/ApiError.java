package com.hms.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * RFC 9457-shaped error body. Every service returns this on failure so the UI
 * and other services only ever parse one error format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String type, String title, int status, String detail, String instance, Instant timestamp,
                       String correlationId, Map<String, String> errors) {

    public static ApiError of(int status, String title, String detail, String instance, String correlationId) {
        return new ApiError("about:blank", title, status, detail, instance, Instant.now(), correlationId, null);
    }

    public ApiError withFieldErrors(Map<String, String> fieldErrors) {
        return new ApiError(type, title, status, detail, instance, timestamp, correlationId, fieldErrors);
    }
}
