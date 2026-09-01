package com.hms.patient.web;

import com.hms.patient.service.PatientService;
import com.hms.patient.web.dto.PatientDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers a suspected duplicate registration with 409 and the candidate charts, so the front desk
 * can merge or confirm rather than being told only that something went wrong.
 */
@RestControllerAdvice
public class DuplicatePatientHandler {

    @ExceptionHandler(PatientService.DuplicatePatientException.class)
    public ResponseEntity<PatientDtos.DuplicateWarning> onDuplicate(PatientService.DuplicatePatientException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new PatientDtos.DuplicateWarning(ex.getMessage() + ". Resend with forceDuplicate=true to "
                        + "register anyway.", ex.candidates()));
    }
}
