package com.hms.common.events;

/**
 * Publishes domain events. Two implementations exist so a developer can run the whole platform
 * with nothing but Postgres: {@link KafkaEventPublisher} in deployed environments and
 * {@link LoggingEventPublisher} when {@code hms.events.transport=log}.
 */
public interface EventPublisher {

    void publish(String topic, DomainEvent event);
}
