package com.hms.notification.web;

import com.hms.common.api.PageResponse;
import com.hms.common.security.Roles;
import com.hms.notification.domain.NotificationEnums;
import com.hms.notification.service.SecureMessagingService;
import com.hms.notification.web.dto.MessagingDtos;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The hospital's side of secure messaging.
 *
 * <p>{@link Roles#NOTIFY_SEND}, which is the same list that may send a patient a notification, and
 * that is the right comparison: answering a patient's question in writing and telling them their
 * report is ready are the same kind of act by the same people. It is deliberately not
 * {@code CLINICAL_READ} — a bench technician has no business in a patient's correspondence — and
 * deliberately not narrower either, because a queue only one role can answer is a queue that goes
 * unanswered whenever that role is busy.
 *
 * <p>Under {@code /notifications} rather than {@code /portal}: everything under {@code /portal} is
 * reached by a patient, and a staff endpoint there would be one {@code @PreAuthorize} away from
 * being the exception that proves it.
 */
@RestController
@RequestMapping("/notifications/messages")
@PreAuthorize(Roles.NOTIFY_SEND)
public class StaffMessagingController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SecureMessagingService messaging;

    public StaffMessagingController(SecureMessagingService messaging) {
        this.messaging = messaging;
    }

    /** The queue, oldest first. Filter by status, or omit it to see everything including closed. */
    @GetMapping
    public PageResponse<MessagingDtos.ThreadSummary> queue(
            @RequestParam(required = false) NotificationEnums.ThreadStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(
                messaging.queue(status, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE))),
                thread -> thread);
    }

    @GetMapping("/{id}")
    public MessagingDtos.ThreadDetail read(@PathVariable UUID id) {
        return messaging.readAsStaff(id);
    }

    @PostMapping("/{id}/replies")
    public MessagingDtos.ThreadDetail reply(@PathVariable UUID id,
                                            @Valid @RequestBody MessagingDtos.ReplyRequest request) {
        return messaging.replyAsStaff(id, request.body());
    }

    @PostMapping("/{id}/close")
    public MessagingDtos.ThreadDetail close(@PathVariable UUID id) {
        return messaging.close(id);
    }
}
