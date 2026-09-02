package com.hms.notification.channel;

import com.hms.notification.domain.NotificationEnums;

/**
 * A way out of the platform.
 *
 * <p>One method, and it takes a {@link Recipient} and an already-rendered {@link Message}. The
 * narrowness is the design: an implementation cannot reach a patient record, a laboratory result or
 * a template, so no channel can add anything to a message. Whether an outbound message carries PHI
 * is therefore decided in exactly one place.
 *
 * <p>Implementations must not throw. A mail server that is down is an operational fact about one
 * message, and letting it propagate would roll back the transaction that was recording the failure
 * — losing the only evidence that the patient was not told. So a channel answers
 * {@link Delivery#failed} and says why.
 */
public interface NotificationChannel {

    NotificationEnums.Channel kind();

    Delivery send(Recipient recipient, Message message);
}
