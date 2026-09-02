package com.hms.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hms.common.error.BadRequestException;
import com.hms.notification.channel.Message;
import com.hms.notification.domain.MessageTemplate;
import com.hms.notification.domain.NotificationEnums;
import com.hms.notification.repo.MessageTemplateRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The closed placeholder set, which is where the module's PHI rule actually lives.
 *
 * <p>No Spring and no database: the rule is a pure function of a template and a substitution map,
 * and it is the single most important thing in the service to be able to check quickly.
 */
class MessageComposerTest {

    private static final NotificationEnums.Category CATEGORY =
            NotificationEnums.Category.LAB_REPORT_READY;
    private static final NotificationEnums.Channel CHANNEL = NotificationEnums.Channel.SMS;

    private MessageComposer composerFor(String body) {
        return composerFor(body, null);
    }

    private MessageComposer composerFor(String body, String subject) {
        MessageTemplate template = mock(MessageTemplate.class);
        when(template.isActive()).thenReturn(true);
        when(template.getBody()).thenReturn(body);
        when(template.getSubject()).thenReturn(subject);

        MessageTemplateRepository templates = mock(MessageTemplateRepository.class);
        when(templates.findByCategoryAndChannel(CATEGORY, CHANNEL)).thenReturn(Optional.of(template));
        return new MessageComposer(templates, "https://portal.example/reports");
    }

    @Test
    @DisplayName("the portal link is always available without the caller supplying it")
    void portalUrlIsAlwaysSubstituted() {
        Optional<Message> message = composerFor("A report is ready: {portalUrl}")
                .compose(CATEGORY, CHANNEL, Map.of());

        assertThat(message).isPresent();
        assertThat(message.get().body()).isEqualTo("A report is ready: https://portal.example/reports");
    }

    @Test
    @DisplayName("a date can be interpolated, because a date is not a clinical finding")
    void whenIsAllowed() {
        Optional<Message> message = composerFor("Your appointment is on {when}. {portalUrl}")
                .compose(CATEGORY, CHANNEL, Map.of("when", "12 March, 10:30"));

        assertThat(message.orElseThrow().body())
                .isEqualTo("Your appointment is on 12 March, 10:30. https://portal.example/reports");
    }

    @Nested
    @DisplayName("a template that would put clinical information into a message")
    class RefusedPlaceholders {

        @Test
        @DisplayName("a result value is refused, and the refusal says why")
        void valueIsRefused() {
            assertThatThrownBy(() -> composerFor("Your haemoglobin is {value}. {portalUrl}")
                    .compose(CATEGORY, CHANNEL, Map.of("value", "9.6")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("{value}")
                    .hasMessageContaining("never states what it says");
        }

        @Test
        @DisplayName("a diagnosis is refused")
        void diagnosisIsRefused() {
            assertThatThrownBy(() -> composerFor("Regarding your {diagnosis}: {portalUrl}")
                    .compose(CATEGORY, CHANNEL, Map.of("diagnosis", "anaemia")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("{diagnosis}");
        }

        @Test
        @DisplayName("a name is refused — an SMS to a shared handset names nobody")
        void patientNameIsRefused() {
            assertThatThrownBy(() -> composerFor("Dear {patientName}, {portalUrl}")
                    .compose(CATEGORY, CHANNEL, Map.of("patientName", "Meera Nair")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("{patientName}");
        }

        @Test
        @DisplayName("the subject line is held to the same rule as the body")
        void subjectIsCheckedToo() {
            // Worth its own case: an email subject is the part shown on a locked screen, so it is
            // the *most* exposed text in the whole message rather than the least.
            assertThatThrownBy(() -> composerFor("{portalUrl}", "Your {value} result")
                    .compose(CATEGORY, CHANNEL, Map.of("value", "9.6")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("{value}");
        }
    }

    @Test
    @DisplayName("a value the caller offers that no template may use never reaches the message")
    void extraValuesAreIgnoredRatherThanRefused() {
        // Ignored, not refused: a caller passing context it happens to have is not making a
        // mistake. What matters is that it cannot appear in the words.
        Optional<Message> message = composerFor("A report is ready: {portalUrl}")
                .compose(CATEGORY, CHANNEL, Map.of("value", "9.6", "mrn", "MRN-000123"));

        assertThat(message.orElseThrow().body())
                .doesNotContain("9.6")
                .doesNotContain("MRN-000123");
    }

    @Test
    @DisplayName("an allowed placeholder with nothing to substitute is a loud failure")
    void missingValueIsRefused() {
        // Rather than rendering a literal "{when}" to a patient, which reads as a broken system
        // and is a broken system.
        assertThatThrownBy(() -> composerFor("Your appointment is on {when}")
                .compose(CATEGORY, CHANNEL, Map.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("{when}");
    }

    @Test
    @DisplayName("a switched-off template composes nothing at all")
    void inactiveTemplateComposesNothing() {
        MessageTemplate template = mock(MessageTemplate.class);
        when(template.isActive()).thenReturn(false);
        MessageTemplateRepository templates = mock(MessageTemplateRepository.class);
        when(templates.findByCategoryAndChannel(CATEGORY, CHANNEL)).thenReturn(Optional.of(template));

        assertThat(new MessageComposer(templates, "https://portal.example")
                .compose(CATEGORY, CHANNEL, Map.of())).isEmpty();
    }
}
