package com.hms.notification.service;

import com.hms.common.audit.AuditService;
import com.hms.common.error.BadRequestException;
import com.hms.common.error.NotFoundException;
import com.hms.notification.domain.MessageTemplate;
import com.hms.notification.repo.MessageTemplateRepository;
import com.hms.notification.web.dto.NotificationDtos;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rewording the platform's voice.
 *
 * <p>The interesting part is the validation. A stored template is rendered later, by
 * {@link MessageComposer}, which refuses a placeholder outside its closed set — but discovering
 * that at render time means discovering it when a patient should have been told something and was
 * not. So the same check runs here, when somebody writes the template, where the error can name
 * what they did.
 */
@Service
public class TemplateService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");

    private final MessageTemplateRepository templates;
    private final AuditService audit;
    private final ObjectProvider<com.hms.common.client.ServiceTokenProvider> serviceAccount;

    public TemplateService(MessageTemplateRepository templates, AuditService audit,
                           ObjectProvider<com.hms.common.client.ServiceTokenProvider> serviceAccount) {
        this.templates = templates;
        this.audit = audit;
        this.serviceAccount = serviceAccount;
    }

    /** Whether patient contact lookup is wired up. Reported so a screen can say so plainly. */
    public boolean contactLookupConfigured() {
        return serviceAccount.getIfAvailable() != null;
    }

    @Transactional
    public MessageTemplate update(UUID id, NotificationDtos.UpdateTemplateRequest request) {
        MessageTemplate template = templates.findById(id)
                .orElseThrow(() -> NotFoundException.of("MessageTemplate", id));

        validate(request.subject());
        validate(request.body());

        template.reword(request.subject(), request.body());
        if (request.active() != null) {
            template.setActive(request.active());
        }
        MessageTemplate saved = templates.save(template);
        audit.record("MESSAGE_TEMPLATE_UPDATED", "MessageTemplate", saved.getId(),
                "%s on %s%s".formatted(saved.getCategory(), saved.getChannel(),
                        saved.isActive() ? "" : " (switched off)"));
        return saved;
    }

    private static void validate(String text) {
        if (text == null) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!MessageComposer.ALLOWED.contains(name)) {
                throw new BadRequestException(("'{%s}' is not one of the values a message may carry"
                        + " (%s). An outbound message says that something is ready and where to see"
                        + " it; it never says what it says, because a phone number is often shared"
                        + " and SMS is plaintext to the handset.")
                        .formatted(name, String.join(", ", MessageComposer.ALLOWED)));
            }
        }
    }
}
