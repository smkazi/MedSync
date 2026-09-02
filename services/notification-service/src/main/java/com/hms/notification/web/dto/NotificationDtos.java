package com.hms.notification.web.dto;

import com.hms.notification.domain.NotificationEnums;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    /**
     * Asking the platform to tell a patient something.
     *
     * <p><strong>There is no body field, and that is the design.</strong> A caller chooses a
     * category and the words come from a template — because the module's rule is that an outbound
     * message carries no protected health information, and a rule that depended on what somebody
     * typed into a free-text box would not be a rule. If a clinician needs to say something
     * specific, the specific thing goes in the portal message behind a sign-in and this tells the
     * patient to go and read it.
     *
     * @param when             for the appointment categories: the date and time, already formatted
     *                         for a reader. A date is not a clinical finding, which is why it is
     *                         one of exactly two values a template may interpolate.
     * @param idempotencyKey   optional. Supply one and a retried request cannot double-send;
     *                         omit it and the platform derives one from the category, patient and
     *                         reference, which makes an accidental double-click safe.
     */
    public record SendRequest(
            @NotNull NotificationEnums.Category category,
            @NotNull NotificationEnums.Channel channel,
            @NotNull UUID patientId,
            @Size(max = 64) String reference,
            @Size(max = 64) String when,
            @Size(max = 120) String idempotencyKey) {
    }

    public record NotificationResponse(UUID id, NotificationEnums.Channel channel,
                                       NotificationEnums.Category category, String recipient,
                                       String subject, String body, NotificationEnums.Status status,
                                       int attempts, UUID patientId, String reference,
                                       Instant createdAt, Instant sentAt, String failedReason) {
    }

    /**
     * What this deployment can actually do.
     *
     * <p>Exposed so a screen does not offer a channel that does not exist. The platform falls back
     * to the log rather than refusing, so without this a user would pick SMS, see "sent", and be
     * wrong about what happened.
     */
    public record CapabilityResponse(Set<NotificationEnums.Channel> channels,
                                     boolean contactLookupConfigured) {
    }

    public record TemplateResponse(UUID id, NotificationEnums.Category category,
                                   NotificationEnums.Channel channel, String subject, String body,
                                   boolean active) {
    }

    /** Rewording a template. The category and channel are its key and are not editable. */
    public record UpdateTemplateRequest(@Size(max = 200) String subject,
                                        @Size(max = 1000) String body,
                                        Boolean active) {
    }
}
