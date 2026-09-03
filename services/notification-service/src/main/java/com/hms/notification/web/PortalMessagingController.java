package com.hms.notification.web;

import com.hms.common.security.CurrentUser;
import com.hms.common.security.Roles;
import com.hms.notification.client.PortalIdentityClient;
import com.hms.notification.service.SecureMessagingService;
import com.hms.notification.web.dto.MessagingDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The patient's side of secure messaging.
 *
 * <p>No patient id in any signature. The inbox is the session's, a new thread is filed under the
 * session's patient, and a reply is refused unless the thread already belongs to them.
 */
@RestController
@RequestMapping("/portal/messages")
@PreAuthorize(Roles.PORTAL)
public class PortalMessagingController {

    private final SecureMessagingService messaging;
    private final PortalIdentityClient identity;

    public PortalMessagingController(SecureMessagingService messaging, PortalIdentityClient identity) {
        this.messaging = messaging;
        this.identity = identity;
    }

    @GetMapping
    public List<MessagingDtos.ThreadSummary> inbox() {
        return messaging.inboxFor(CurrentUser.requirePatientId());
    }

    /** The badge on the portal's front page. Its own endpoint so drawing it costs one small read. */
    @GetMapping("/unread")
    public MessagingDtos.UnreadCount unread() {
        return new MessagingDtos.UnreadCount(messaging.unreadFor(CurrentUser.requirePatientId()));
    }

    /** Opening a thread is what reading it means, so this marks its staff messages read. */
    @GetMapping("/{id}")
    public MessagingDtos.ThreadDetail read(@PathVariable UUID id) {
        return messaging.readAsPatient(CurrentUser.requirePatientId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessagingDtos.ThreadDetail start(@Valid @RequestBody MessagingDtos.StartThreadRequest request,
                                            HttpServletRequest httpRequest) {
        UUID patientId = CurrentUser.requirePatientId();
        PortalIdentityClient.PortalIdentity me = identity.require(bearer(httpRequest));
        if (!me.id().equals(patientId)) {
            throw new IllegalStateException(
                    "The signed-in patient and the record read for them do not match");
        }
        return messaging.start(patientId, me.mrn(), request);
    }

    @PostMapping("/{id}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    public MessagingDtos.ThreadDetail reply(@PathVariable UUID id,
                                            @Valid @RequestBody MessagingDtos.ReplyRequest request) {
        return messaging.replyAsPatient(CurrentUser.requirePatientId(), id, request.body());
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header == null ? "" : header;
    }
}
