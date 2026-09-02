package com.hms.notification.channel;

import com.hms.notification.domain.NotificationEnums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Email, over plain SMTP.
 *
 * <p>SMTP rather than a vendor SDK on purpose. Every hospital already has a mail server or a
 * mailbox with a provider, SMTP is what all of them speak, and it costs nothing — where a vendor
 * SDK would add a paid dependency, an API key, and a client library that has to be mocked to test
 * anything. The same reasoning as {@link HttpGatewayChannel}.
 *
 * <p>Only present when a mail host is configured. Without one the bean does not exist and
 * {@code ChannelRegistry} falls back to the logging channel, which is a working deployment rather
 * than a broken one.
 *
 * <p>Plain text, not HTML. An HTML mail is a rendering surface, and a rendering surface that
 * interpolates anything is a place an injection lives; there is nothing here worth the risk when
 * the whole message is one sentence and a link.
 */
@Component
@ConditionalOnExpression("!'${spring.mail.host:}'.isEmpty()")
public class SmtpChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SmtpChannel.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpChannel(JavaMailSender mailSender,
                       @Value("${hms.notification.from:no-reply@localhost}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public NotificationEnums.Channel kind() {
        return NotificationEnums.Channel.EMAIL;
    }

    @Override
    public Delivery send(Recipient recipient, Message message) {
        if (!recipient.hasEmail()) {
            return Delivery.failed(null, "No email address on file");
        }
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(from);
            mail.setTo(recipient.email());
            mail.setSubject(message.subject() == null ? "A message from the hospital" : message.subject());
            mail.setText(message.body());
            mailSender.send(mail);
            return Delivery.sent(recipient.email(), "accepted by " + from);
        } catch (RuntimeException ex) {
            // Never rethrown: the transaction recording this failure is the only evidence that the
            // patient was not told, and rolling it back would destroy that evidence.
            log.warn("SMTP delivery failed for patient {}: {}", recipient.patientId(), ex.getMessage());
            return Delivery.failed(recipient.email(), ex.getMessage());
        }
    }
}
