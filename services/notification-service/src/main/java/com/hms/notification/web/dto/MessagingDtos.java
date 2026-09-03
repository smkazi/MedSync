package com.hms.notification.web.dto;

import com.hms.notification.domain.NotificationEnums;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for secure messaging, on both sides of the conversation. */
public final class MessagingDtos {

    private MessagingDtos() {
    }

    /**
     * The standing notice, in one place so both the API and the browser say the same words.
     *
     * <p>Returned on every thread response rather than only on the compose screen, because the
     * person who most needs to read it is the one already typing about chest pain — and they got
     * to the reply box from an email, not from the page that carried the warning.
     */
    public static final String NOT_FOR_EMERGENCIES =
            "Messages are read during working hours and are not monitored continuously. If this is "
                    + "urgent or you feel unwell, telephone the hospital or come to casualty.";

    public record StartThreadRequest(
            @NotBlank @Size(max = 160) String subject,
            /** Which department should answer, or blank for general enquiries. */
            @Size(max = 16) String departmentCode,
            @NotBlank @Size(max = 4000) String body) {
    }

    public record ReplyRequest(@NotBlank @Size(max = 4000) String body) {
    }

    /**
     * A thread in a list.
     *
     * <p>Carries the subject and never a message body. An inbox that previewed the first line of
     * each conversation would put a clinical sentence into every screenshot, every shoulder-surf
     * and every browser's back-button cache of the list page.
     */
    public record ThreadSummary(UUID id, UUID patientId, String patientMrn, String subject,
                                String departmentCode, NotificationEnums.ThreadStatus status,
                                Instant lastMessageAt, int messageCount, boolean unreadByPatient) {
    }

    /**
     * A whole conversation, with the standing notice attached to it.
     *
     * <p>The notice is a bean accessor rather than a record component, and it took two attempts to
     * get there. A plain {@code notice()} method never reached the client at all — Jackson
     * serialises a record's components and its bean getters, and {@code notice()} is neither.
     * Making it a component and overwriting it in the compact constructor did reach the client and
     * was a dead store, which SpotBugs reported and was right to: a parameter whose value is thrown
     * away is a parameter that should not exist.
     *
     * <p>{@code getNotice()} is both correct and stronger than either. It is serialised because it
     * is a bean getter, it takes no argument so no caller can suppress it or reword it into
     * something softer, and there is nothing to overwrite.
     */
    public record ThreadDetail(UUID id, UUID patientId, String patientMrn, String subject,
                               String departmentCode, NotificationEnums.ThreadStatus status,
                               Instant lastMessageAt, Instant closedAt,
                               List<ThreadMessageResponse> messages) {

        public ThreadDetail {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }

        /** The standing notice, on every thread the portal draws. */
        public String getNotice() {
            return NOT_FOR_EMERGENCIES;
        }
    }

    public record ThreadMessageResponse(UUID id, NotificationEnums.AuthorKind authorKind,
                                        String authorName, String body, Instant sentAt,
                                        Instant readByPatientAt) {
    }

    /** The portal's front-page badge: how many answers are waiting to be read. */
    public record UnreadCount(long unread) {
    }
}
