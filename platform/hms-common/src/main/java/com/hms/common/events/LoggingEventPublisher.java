package com.hms.common.events;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** No-broker fallback: writes the event as one JSON log line. Used for local dev and tests. */
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    private final ObjectMapper objectMapper;

    public LoggingEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(String topic, DomainEvent event) {
        try {
            log.info("[event] topic={} {}", topic, objectMapper.writeValueAsString(event));
        } catch (JacksonException ex) {
            log.warn("[event] topic={} type={} (payload not serializable: {})", topic, event.type(), ex.getMessage());
        }
    }
}
