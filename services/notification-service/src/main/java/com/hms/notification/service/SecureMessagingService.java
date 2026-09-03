package com.hms.notification.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.ConflictException;
import com.hms.common.error.NotFoundException;
import com.hms.common.security.CurrentUser;
import com.hms.notification.domain.MessageThread;
import com.hms.notification.domain.NotificationEnums;
import com.hms.notification.domain.ThreadMessage;
import com.hms.notification.repo.MessageThreadRepository;
import com.hms.notification.web.dto.MessagingDtos;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two-way messaging between a patient and the hospital.
 *
 * <p>The one thing this service does <em>not</em> do is tell anybody a message has arrived. That is
 * {@link NotificationService}'s job and it goes out through the PHI-free channel, saying only that
 * there is something waiting in the portal — which is the whole reason these two things live in one
 * service and are separate code paths. A notification that quoted the message body would take the
 * sentence this service exists to protect and put it on a handset.
 *
 * <p><strong>Not for emergencies</strong>, and the platform says so on every screen rather than
 * pretending a queue is a triage. Nothing here is read on a schedule, and a patient describing
 * chest pain in a message they expect somebody to see tonight is the failure mode worth designing
 * against; the wording is in {@link MessagingDtos#NOT_FOR_EMERGENCIES} so there is one copy of it.
 */
@Service
public class SecureMessagingService {

    private final MessageThreadRepository threads;
    private final AuditService audit;

    public SecureMessagingService(MessageThreadRepository threads, AuditService audit) {
        this.threads = threads;
        this.audit = audit;
    }

    // ---- the patient's side ----------------------------------------------------

    @Transactional(readOnly = true)
    public List<MessagingDtos.ThreadSummary> inboxFor(UUID patientId) {
        return threads.findByPatientIdOrderByLastMessageAtDesc(patientId).stream()
                .map(SecureMessagingService::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadFor(UUID patientId) {
        return threads.unreadFor(patientId, NotificationEnums.AuthorKind.STAFF);
    }

    /**
     * Opens the thread and marks everything in it as read.
     *
     * <p>Not read-only, because opening a thread is what "read" means. The alternative was a
     * separate "mark as read" call that a client could forget, at which point the unread badge
     * becomes a number nobody believes and everybody ignores.
     */
    @Transactional
    public MessagingDtos.ThreadDetail readAsPatient(UUID patientId, UUID threadId) {
        MessageThread thread = ownedBy(patientId, threadId);
        thread.markReadByPatient();
        return toDetail(thread);
    }

    @Transactional
    public MessagingDtos.ThreadDetail start(UUID patientId, String patientMrn,
                                            MessagingDtos.StartThreadRequest request) {
        MessageThread thread = new MessageThread(patientId, patientMrn,
                request.subject().trim(), blankToNull(request.departmentCode()));
        thread.append(NotificationEnums.AuthorKind.PATIENT, patientMrn, request.body().trim());
        threads.save(thread);
        // The subject, never the body. An audit line is read by more people than the thread is,
        // and "a patient asked something about their discharge medicines" is all it has to say.
        audit.record("PORTAL_THREAD_STARTED", "MessageThread", thread.getId(),
                "subject: " + thread.getSubject());
        return toDetail(thread);
    }

    @Transactional
    public MessagingDtos.ThreadDetail replyAsPatient(UUID patientId, UUID threadId, String body) {
        MessageThread thread = ownedBy(patientId, threadId);
        refuseIfClosed(thread);
        thread.append(NotificationEnums.AuthorKind.PATIENT, thread.getPatientMrn(), body.trim());
        return toDetail(thread);
    }

    // ---- the hospital's side ---------------------------------------------------

    @Transactional(readOnly = true)
    public Page<MessagingDtos.ThreadSummary> queue(NotificationEnums.ThreadStatus status,
                                                   Pageable pageable) {
        return threads.queue(status == null, status, pageable).map(SecureMessagingService::toSummary);
    }

    @Transactional(readOnly = true)
    public MessagingDtos.ThreadDetail readAsStaff(UUID threadId) {
        return toDetail(threads.findById(threadId)
                .orElseThrow(() -> NotFoundException.of("Message thread", threadId)));
    }

    @Transactional
    public MessagingDtos.ThreadDetail replyAsStaff(UUID threadId, String body) {
        MessageThread thread = threads.findById(threadId)
                .orElseThrow(() -> NotFoundException.of("Message thread", threadId));
        refuseIfClosed(thread);
        thread.append(NotificationEnums.AuthorKind.STAFF, CurrentUser.usernameOrSystem(), body.trim());
        audit.record("PORTAL_THREAD_ANSWERED", "MessageThread", threadId,
                "answered by " + CurrentUser.usernameOrSystem());
        return toDetail(thread);
    }

    /**
     * Closes a thread. Staff only, and final.
     *
     * <p>A patient cannot close one, which looks like an omission and is not: a thread the patient
     * closed would leave the hospital unable to add the answer it was still writing, and "I no
     * longer need this" is a sentence they can send.
     */
    @Transactional
    public MessagingDtos.ThreadDetail close(UUID threadId) {
        MessageThread thread = threads.findById(threadId)
                .orElseThrow(() -> NotFoundException.of("Message thread", threadId));
        if (thread.isClosed()) {
            throw new ConflictException("This conversation is already closed");
        }
        thread.close();
        audit.record("PORTAL_THREAD_CLOSED", "MessageThread", threadId,
                "closed by " + CurrentUser.usernameOrSystem());
        return toDetail(thread);
    }

    // ---- shared ----------------------------------------------------------------

    /**
     * The thread, or a 404 if it is not this patient's.
     *
     * <p>404 rather than 403, for the reason every ownership check in the portal answers 404: a
     * thread id that comes back "not yours" is a thread id confirmed to exist, and a conversation
     * between the hospital and a named stranger is worth more to somebody enumerating ids than the
     * distinction between the two status codes is worth to anybody honest.
     */
    private MessageThread ownedBy(UUID patientId, UUID threadId) {
        return threads.findById(threadId)
                .filter(thread -> patientId.equals(thread.getPatientId()))
                .orElseThrow(() -> NotFoundException.of("Message thread", threadId));
    }

    private static void refuseIfClosed(MessageThread thread) {
        if (thread.isClosed()) {
            throw new ConflictException(
                    "This conversation has been closed. Please start a new one — it keeps each "
                            + "question with its own answer.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static MessagingDtos.ThreadSummary toSummary(MessageThread thread) {
        return new MessagingDtos.ThreadSummary(thread.getId(), thread.getPatientId(),
                thread.getPatientMrn(), thread.getSubject(), thread.getDepartmentCode(),
                thread.getStatus(), thread.getLastMessageAt(), thread.getMessages().size(),
                thread.getMessages().stream()
                        .anyMatch(message -> message.getAuthorKind() == NotificationEnums.AuthorKind.STAFF
                                && message.getReadByPatientAt() == null));
    }

    private static MessagingDtos.ThreadDetail toDetail(MessageThread thread) {
        return new MessagingDtos.ThreadDetail(thread.getId(), thread.getPatientId(),
                thread.getPatientMrn(), thread.getSubject(), thread.getDepartmentCode(),
                thread.getStatus(), thread.getLastMessageAt(), thread.getClosedAt(),
                thread.getMessages().stream().map(SecureMessagingService::toMessage).toList());
    }

    private static MessagingDtos.ThreadMessageResponse toMessage(ThreadMessage message) {
        return new MessagingDtos.ThreadMessageResponse(message.getId(), message.getAuthorKind(),
                message.getAuthorName(), message.getBody(), message.getSentAt(),
                message.getReadByPatientAt());
    }
}
