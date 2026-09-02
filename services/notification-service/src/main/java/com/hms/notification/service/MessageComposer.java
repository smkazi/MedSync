package com.hms.notification.service;

import com.hms.common.error.BadRequestException;
import com.hms.notification.channel.Message;
import com.hms.notification.domain.MessageTemplate;
import com.hms.notification.domain.NotificationEnums;
import com.hms.notification.repo.MessageTemplateRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a category into the words that leave the platform.
 *
 * <p><strong>This class is the PHI rule.</strong> The module's shaping constraint is that an
 * outbound message carries no protected health information: not a value, not a flag, not a
 * diagnosis, not a name. The reasoning is that a phone number is often stale, is frequently shared
 * within a family, and SMS is plaintext to the handset — so "your haemoglobin is 9.6, which is low"
 * is a disclosure to whoever happens to be holding the phone, while "a report is ready, sign in to
 * view it" is not.
 *
 * <p>A rule stated in prose is a rule that erodes. This one is enforced by construction, in two
 * halves:
 *
 * <ul>
 *   <li><strong>Callers supply no text.</strong> Neither the API nor the event consumer passes a
 *       body. They pass a {@link NotificationEnums.Category}, and the words come from a template.
 *   <li><strong>Templates may interpolate only a closed set.</strong> {@link #ALLOWED} is two
 *       placeholders wide, and rendering refuses anything else — so rewording a template cannot
 *       introduce {@code {diagnosis}} or {@code {value}}, and a typo becomes a loud failure rather
 *       than a literal brace in a patient's inbox.
 * </ul>
 *
 * <p>Why {@code when} is allowed and a diagnosis is not: an appointment date is not a clinical
 * finding. Somebody reading a shared handset learns that the person has an appointment, which they
 * would learn from the reminder existing at all; they do not learn what it is for, who it is with,
 * or anything a result said. The line is drawn at "what the visit was about", and it is drawn here.
 */
@Service
public class MessageComposer {

    /**
     * Everything a template may interpolate.
     *
     * <p>Deliberately two entries. {@code portalUrl} is where the patient goes to read the thing
     * this message is telling them exists — the whole design depends on there being somewhere
     * behind a sign-in to point at. {@code when} is a date and time and nothing else.
     *
     * <p>Adding to this set is the one change that could break the PHI rule, so it should not
     * happen without deciding, in writing, that the new value is not a clinical fact.
     */
    public static final Set<String> ALLOWED = Set.of("portalUrl", "when");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)}");

    private final MessageTemplateRepository templates;
    private final String portalUrl;

    public MessageComposer(MessageTemplateRepository templates,
                           @Value("${hms.notification.portal-url:http://localhost:3000}") String portalUrl) {
        this.templates = templates;
        this.portalUrl = portalUrl;
    }

    /**
     * Renders the template for this category and channel.
     *
     * @param values substitutions the caller offers. Anything not in {@link #ALLOWED} is ignored
     *               rather than refused — a caller passing extra context is not an error, it just
     *               does not reach the message.
     */
    @Transactional(readOnly = true)
    public Optional<Message> compose(NotificationEnums.Category category,
                                     NotificationEnums.Channel channel,
                                     Map<String, String> values) {
        Optional<MessageTemplate> found = templates.findByCategoryAndChannel(category, channel);
        if (found.isEmpty() || !found.get().isActive()) {
            return Optional.empty();
        }
        MessageTemplate template = found.get();
        Map<String, String> substitutions = new java.util.HashMap<>();
        substitutions.put("portalUrl", portalUrl);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (ALLOWED.contains(entry.getKey()) && entry.getValue() != null) {
                substitutions.put(entry.getKey(), entry.getValue());
            }
        }
        return Optional.of(new Message(
                template.getSubject() == null ? null : render(template.getSubject(), substitutions),
                render(template.getBody(), substitutions)));
    }

    /**
     * Substitutes the placeholders, and refuses any that is not allowed.
     *
     * <p>Refuses rather than leaves it in place: a body reaching a patient with a literal
     * {@code {value}} in it is a bug that looks like a cosmetic one and is not — it means somebody
     * tried to put a value in a message, and the right time to find that out is when the template
     * is rendered rather than when a patient reads it.
     */
    private static String render(String template, Map<String, String> substitutions) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!ALLOWED.contains(name)) {
                throw new BadRequestException(("This message template uses '{%s}', which is not one of"
                        + " the values a message may carry (%s). An outbound message states that"
                        + " something is ready and where to see it; it never states what it says.")
                        .formatted(name, String.join(", ", ALLOWED)));
            }
            String value = substitutions.get(name);
            if (value == null) {
                throw new BadRequestException(
                        "This message needs a value for '{%s}' and none was supplied".formatted(name));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
