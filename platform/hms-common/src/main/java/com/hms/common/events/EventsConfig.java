package com.hms.common.events;

import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/** Selects the event transport from {@code hms.events.transport} (kafka | log, default log). */
@Configuration
public class EventsConfig {

    @Bean
    @ConditionalOnProperty(name = "hms.events.transport", havingValue = "kafka")
    public EventPublisher kafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        return new KafkaEventPublisher(kafkaTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher loggingEventPublisher(ObjectMapper objectMapper) {
        return new LoggingEventPublisher(objectMapper);
    }
}
