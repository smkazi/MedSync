package com.hms.notification.web;

import com.hms.common.api.PageResponse;
import com.hms.common.security.Roles;
import com.hms.notification.channel.ChannelRegistry;
import com.hms.notification.domain.NotificationEnums;
import com.hms.notification.repo.MessageTemplateRepository;
import com.hms.notification.service.NotificationService;
import com.hms.notification.service.TemplateService;
import com.hms.notification.web.dto.NotificationDtos;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Outbound messaging: send one, read the delivery log, retune the wording. */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService service;
    private final TemplateService templates;
    private final MessageTemplateRepository templateRepository;
    private final ChannelRegistry channels;
    private final NotificationMapper mapper;

    public NotificationController(NotificationService service, TemplateService templates,
                                  MessageTemplateRepository templateRepository,
                                  ChannelRegistry channels, NotificationMapper mapper) {
        this.service = service;
        this.templates = templates;
        this.templateRepository = templateRepository;
        this.channels = channels;
        this.mapper = mapper;
    }

    /**
     * Sends one message.
     *
     * <p>201 even for a {@code SUPPRESSED} result, because something was created: a delivery record
     * saying nothing was sent and why. Answering 4xx would suggest the request was wrong, and "this
     * patient has no phone number on file" is not the caller's mistake.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.NOTIFY_SEND)
    public NotificationDtos.NotificationResponse send(@Valid @RequestBody NotificationDtos.SendRequest request) {
        String key = request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                // Derived rather than random: a double-clicked button posts the same request twice
                // and must not send two messages. Coarse on purpose - the same category for the
                // same patient about the same thing is the same message.
                ? "api:%s:%s:%s".formatted(request.category(), request.patientId(),
                        request.reference() == null ? "-" : request.reference())
                : request.idempotencyKey().trim();

        return mapper.toResponse(service.send(new NotificationService.Request(
                request.category(), request.channel(), request.patientId(), request.reference(),
                key, request.when() == null ? Map.of() : Map.of("when", request.when()))));
    }

    /** The delivery log, newest first. */
    @GetMapping
    @PreAuthorize(Roles.NOTIFY_READ)
    public PageResponse<NotificationDtos.NotificationResponse> log(
            @RequestParam(required = false) NotificationEnums.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(service.log(status, PageRequest.of(page, Math.min(size, 200))),
                mapper::toResponse);
    }

    @GetMapping("/patients/{patientId}")
    @PreAuthorize(Roles.NOTIFY_READ)
    public List<NotificationDtos.NotificationResponse> forPatient(@PathVariable UUID patientId) {
        return service.forPatient(patientId).stream().map(mapper::toResponse).toList();
    }

    /**
     * What this deployment can actually send with.
     *
     * <p>Readable by anybody who may send, so a screen can stop offering SMS on a deployment that
     * has no SMS gateway. The platform substitutes the log rather than refusing, which is the right
     * runtime behaviour and the wrong thing to leave a user guessing about.
     */
    @GetMapping("/capabilities")
    @PreAuthorize(Roles.NOTIFY_READ)
    public NotificationDtos.CapabilityResponse capabilities() {
        return new NotificationDtos.CapabilityResponse(channels.available(),
                templates.contactLookupConfigured());
    }

    @GetMapping("/templates")
    @PreAuthorize(Roles.NOTIFY_READ)
    public List<NotificationDtos.TemplateResponse> templates() {
        return templateRepository.findAllByOrderByCategoryAscChannelAsc().stream()
                .map(mapper::toResponse)
                .toList();
    }

    /**
     * Rewords a template.
     *
     * <p>Administrative: this is the platform's voice to a patient. The body is validated against
     * the closed placeholder set before it is stored, so a template that would put a clinical value
     * into a message is refused at the point somebody writes it rather than at the point a patient
     * reads it.
     */
    @PatchMapping("/templates/{id}")
    @PreAuthorize(Roles.ADMIN_ONLY)
    public NotificationDtos.TemplateResponse updateTemplate(
            @PathVariable UUID id,
            @Valid @RequestBody NotificationDtos.UpdateTemplateRequest request) {
        return mapper.toResponse(templates.update(id, request));
    }
}
