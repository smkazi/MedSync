package com.hms.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The platform's public URL map.
 *
 * <p>Routes are declared in Java rather than YAML so the path-to-service mapping is compiled and
 * reviewable in one place. Only these prefixes are reachable from outside: a service's actuator
 * endpoints and any future internal API stay unroutable, so the gateway is not an open proxy.
 */
@Configuration
@EnableConfigurationProperties(ServiceUris.class)
public class RouteConfig {

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder, ServiceUris services) {
        return builder.routes()
                // Authentication, user administration and the published signing keys.
                .route("identity", r -> r
                        .path("/auth/**", "/admin/**", "/.well-known/**")
                        .uri(services.identity()))
                // Patients, clinical staff, departments, and the facility directory.
                //
                // The facility paths sit with patient-service because rooms and beds are master
                // data of the same kind as departments and staff. `/room-types` is the configurable
                // taxonomy, not a resource under `/rooms`, so it needs its own prefix - routing it
                // by accident would have made every new room type a 404.
                .route("patient", r -> r
                        .path("/patients/**", "/staff/**", "/departments/**",
                              "/floors/**", "/rooms/**", "/room-types/**", "/beds/**")
                        .uri(services.patient()))
                // Appointments, encounters, clinical notes, vitals and clinician availability.
                .route("scheduling", r -> r
                        .path("/appointments/**", "/encounters/**", "/schedules/**")
                        .uri(services.scheduling()))
                // Laboratory: orders, specimens, results, catalog and analyzer message ingest.
                .route("laboratory", r -> r
                        .path("/lab/**")
                        .uri(services.laboratory()))
                // Clinical decision support: summarisation, triage, no-show risk, coding.
                .route("ai", r -> r
                        .path("/ai/**")
                        .uri(services.ai()))
                .build();
    }
}
