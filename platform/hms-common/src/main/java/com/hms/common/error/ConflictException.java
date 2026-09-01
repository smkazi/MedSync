package com.hms.common.error;

/** Thrown when a request collides with existing state; mapped to HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
