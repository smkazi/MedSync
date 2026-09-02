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
                //
                // `/queue/**` is the outpatient token board, which is scheduling's because a token
                // is issued by the check-in transition. `/public/**` is the corridor display and is
                // the one prefix on the platform reachable without a token: it is routed
                // explicitly rather than by a wildcard so that adding a second public path is a
                // visible act here as well as in the service's allowlist.
                //
                // `/order-sets/**` and `/care-plans/**` are scheduling's because both hang off an
                // encounter. Applying a set reaches laboratory-service and pharmacy-service with
                // the clinician's own token, so this one route fans out to two others — which is
                // why the response says exactly what was raised in each.
                .route("scheduling", r -> r
                        .path("/appointments/**", "/encounters/**", "/schedules/**",
                              "/queue/**", "/public/queue/**", "/escalation-policies/**",
                              "/order-sets/**", "/care-plans/**")
                        .uri(services.scheduling()))
                // Laboratory: orders, specimens, results, catalog and analyzer message ingest.
                .route("laboratory", r -> r
                        .path("/lab/**")
                        .uri(services.laboratory()))
                // Outbound messaging: the delivery log, and the wording templates behind it.
                .route("notification", r -> r
                        .path("/notifications/**")
                        .uri(services.notification()))
                // Casualty and in-patient care, including the bed map.
                //
                // Two prefixes and no more. The bed map lives at `/casualty/beds` and
                // `/admissions/beds` rather than the better-reading `/beds/casualty`, because
                // `/beds/**` already belongs to patient-service — which owns beds as master data —
                // and the gateway takes the first predicate that matches. Claiming a slice of
                // another service's prefix answered 405 from the wrong service, and ordering the
                // routes to fix it would have left an invisible dependency for whoever next adds
                // a bed sub-resource over there.
                .route("admissions", r -> r
                        .path("/casualty/**", "/admissions/**")
                        .uri(services.admissions()))
                // The medication loop: formulary, prescribing, dispensing, and the bedside eMAR.
                //
                // `/prescriptions/**` sits outside the `/pharmacy/**` prefix deliberately. A
                // prescription is a clinical order written by a prescriber, not a pharmacy record —
                // the pharmacy reads it — and putting it under the pharmacy's prefix would say
                // otherwise in the one place every client sees. `/emar/**` is the ward's end of the
                // same loop and is separate for the same reason.
                .route("pharmacy", r -> r
                        .path("/pharmacy/**", "/prescriptions/**", "/emar/**")
                        .uri(services.pharmacy()))
                // Clinical decision support: summarisation, triage, no-show risk, coding.
                .route("ai", r -> r
                        .path("/ai/**")
                        .uri(services.ai()))
                .build();
    }
}
