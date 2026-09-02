package com.hms.notification.channel;

import com.hms.notification.domain.NotificationEnums;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Which channels this deployment actually has.
 *
 * <p>The adapters are all {@code @ConditionalOnProperty}, so a deployment with no mail server and
 * no SMS contract has exactly one: the log. Asking for a channel that is not configured therefore
 * has to mean something, and what it means is "use the log and record that this is what happened"
 * — not an exception. A released report must not roll back because an SMS gateway was never set up.
 */
@Component
public class ChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChannelRegistry.class);

    private final Map<NotificationEnums.Channel, NotificationChannel> channels =
            new EnumMap<>(NotificationEnums.Channel.class);
    private final NotificationChannel fallback;

    public ChannelRegistry(List<NotificationChannel> discovered, LoggingChannel loggingChannel) {
        for (NotificationChannel channel : discovered) {
            channels.put(channel.kind(), channel);
        }
        this.fallback = loggingChannel;
        log.info("Notification channels available: {}", channels.keySet());
    }

    /** True when the deployment can really use this channel. The screens say so rather than lying. */
    public boolean has(NotificationEnums.Channel kind) {
        return channels.containsKey(kind);
    }

    public java.util.Set<NotificationEnums.Channel> available() {
        return java.util.Set.copyOf(channels.keySet());
    }

    /**
     * The channel to use, and the channel it will actually be.
     *
     * <p>Returns the resolved kind alongside the adapter, because the delivery log must record what
     * happened rather than what was asked for: a row saying SMS when the message went to the log is
     * a row that will be believed.
     */
    public Resolved resolve(NotificationEnums.Channel requested) {
        NotificationChannel found = channels.get(requested);
        if (found != null) {
            return new Resolved(requested, found, null);
        }
        return new Resolved(fallback.kind(), fallback,
                "The " + requested + " channel is not configured on this deployment");
    }

    /**
     * @param substitution why the requested channel was not used, or null when it was
     */
    public record Resolved(NotificationEnums.Channel kind, NotificationChannel channel,
                           String substitution) {
    }
}
