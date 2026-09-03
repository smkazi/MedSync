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
                //
                // `/portal/me` is the first of the `/portal/**` paths, and this is the place to say
                // what that prefix is. It is not a service: it is one door, opened by a patient's
                // own token, onto data that stays in the five services that own it. A portal
                // service would have had to hold a credential able to read every patient's chart
                // in order to assemble one patient's view, and that credential would be the most
                // attractive thing on the platform. Instead each service answers for its own data,
                // reading whose record it is from the `patient_id` claim and never from the
                // request — so the prefix is split here, one sub-path per owner, and every entry
                // below names the service that already holds what it returns.
                .route("patient", r -> r
                        .path("/patients/**", "/staff/**", "/departments/**",
                              "/floors/**", "/rooms/**", "/room-types/**", "/beds/**",
                              "/portal/me", "/portal/me/**")
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
                              "/order-sets/**", "/care-plans/**",
                              "/portal/appointments/**", "/portal/availability",
                              "/portal/encounters/**")
                        .uri(services.scheduling()))
                // Laboratory: orders, specimens, results, catalog and analyzer message ingest.
                .route("laboratory", r -> r
                        .path("/lab/**", "/portal/reports/**")
                        .uri(services.laboratory()))
                // Outbound messaging: the delivery log, and the wording templates behind it.
                .route("notification", r -> r
                        .path("/notifications/**", "/portal/messages/**")
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
                        .path("/pharmacy/**", "/prescriptions/**", "/emar/**",
                              "/portal/prescriptions/**")
                        .uri(services.pharmacy()))
                // The revenue cycle: the price list, invoices, payments and payer claims.
                //
                // Every one of these paths is a top-level noun rather than a `/billing/**` prefix,
                // because that is what they are to a client: an invoice is a document a patient is
                // given and a payment is money that arrived, and neither is a module's internal
                // detail. The service that answers them is a deployment decision this list is the
                // only place that records.
                .route("billing", r -> r
                        .path("/invoices/**", "/charges", "/charge-items/**", "/payers/**",
                                "/tax-rates/**", "/claims/**", "/day-book",
                                "/portal/invoices/**")
                        .uri(services.billing()))
                // Health-information exchange: consent artefacts, FHIR bundles, what leaves.
                //
                // `/consents/**` is a top-level noun for the same reason `/invoices/**` is: a
                // consent is a thing a patient gave and a clinician looks for, not an interop
                // module's internal detail. `/interop/**` is the machinery — sharing, exporting,
                // the disclosure log — and is named as machinery because that is what it is.
                .route("interop", r -> r
                        .path("/consents/**", "/interop/**", "/portal/records/**")
                        .uri(services.interop()))
                // Clinical decision support: summarisation, triage, no-show risk, coding.
                .route("ai", r -> r
                        .path("/ai/**")
                        .uri(services.ai()))
                .build();
    }
}
