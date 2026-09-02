package com.hms.notification.channel;

import com.hms.notification.domain.NotificationEnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The default channel, and not a stub.
 *
 * <p>On a deployment with no mail server and no SMS contract this is the honest answer: the message
 * is composed, recorded in the delivery log exactly as it would have been sent, and written to the
 * service log. A module that refused to start without a paid gateway would be a module nobody could
 * run, and the two things worth testing here — that the right message is composed and that a replay
 * does not double-send — are fully exercised through it.
 *
 * <p>It is also what every test asserts against, deliberately. A test that needed a live SMTP
 * server is a test that gets disabled in CI and then quietly forever.
 *
 * <p>The log line carries the patient's id and never their address or the body, because a service
 * log is read by more people than a delivery log and is shipped to more places.
 */
@Component
public class LoggingChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingChannel.class);

    @Override
    public NotificationEnums.Channel kind() {
        return NotificationEnums.Channel.LOG;
    }

    @Override
    public Delivery send(Recipient recipient, Message message) {
        log.info("Notification for patient {}: {} characters, subject {}",
                recipient.patientId(), message.body().length(),
                message.subject() == null ? "(none)" : "present");
        // The address is what the delivery log records, and for this channel there is genuinely
        // none - so it says so rather than borrowing the phone number it was not going to use.
        return Delivery.sent("log", "recorded, not transmitted");
    }
}
