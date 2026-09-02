package com.hms.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where each service lives. Resolution is DNS-based — Docker Compose service names locally,
 * Kubernetes service names in a cluster — so no service registry is needed.
 */
@ConfigurationProperties(prefix = "hms.services")
public record ServiceUris(String identity, String patient, String scheduling, String laboratory,
                          String notification, String admissions, String pharmacy, String ai) {
}
