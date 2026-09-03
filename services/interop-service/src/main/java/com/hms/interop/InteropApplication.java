package com.hms.interop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Health-information exchange: consent artefacts, FHIR R4 bundles and what leaves the building.
 *
 * <p>Its own service rather than a corner of patient-service, for two reasons. A bundle is composed
 * from four other services' data, and putting that fan-out inside the one service everybody else
 * calls would invert the dependency graph. And an outbound integration with a national network is
 * exactly the kind of thing that should be able to fail, be redeployed, or be turned off without
 * touching a clinical service.
 *
 * <p>{@code scanBasePackages} includes {@code com.hms.common} because that is how the shared audit
 * service, the RFC 9457 error handling, the event publisher and the resource-server security
 * configuration register.
 */
@SpringBootApplication(scanBasePackages = {"com.hms.interop", "com.hms.common"})
public class InteropApplication {

    public static void main(String[] args) {
        SpringApplication.run(InteropApplication.class, args);
    }
}
