package com.hms.common.error;

/** Thrown for semantically invalid input that bean validation cannot express; mapped to HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
