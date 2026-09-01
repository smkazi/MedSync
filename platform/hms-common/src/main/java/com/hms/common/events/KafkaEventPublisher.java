package com.hms.common.events;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes to Kafka keyed by aggregate id, so all events for one patient or appointment land on
 * the same partition and stay ordered. A publish failure is logged, never propagated: losing an
 * audit event must not fail a clinical write.
 */
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(String topic, DomainEvent event) {
        try {
            kafkaTemplate.send(topic, event.aggregateId(), objectMapper.writeValueAsString(event))
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish {} to {}", event.type(), topic, ex);
                        }
                    });
        } catch (JacksonException ex) {
            log.error("Could not serialize event {} for topic {}", event.type(), topic, ex);
        }
    }
}
