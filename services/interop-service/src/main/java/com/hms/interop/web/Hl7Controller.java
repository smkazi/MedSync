package com.hms.interop.web;

import com.hms.common.api.PageResponse;
import com.hms.common.error.BadRequestException;
import com.hms.common.security.Roles;
import com.hms.interop.domain.Hl7Exchange;
import com.hms.interop.hl7.MllpClient;
import com.hms.interop.repo.Hl7ExchangeRepository;
import com.hms.interop.service.Hl7IngestService;
import com.hms.interop.service.Hl7OutboundBuilder;
import com.hms.interop.web.dto.InteropDtos;
import jakarta.validation.Valid;
import java.nio.charset.Charset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HL7 v2 over HTTP, and the message log.
 *
 * <p>The inbound endpoint takes exactly what a sender would have put on a socket, so a system
 * without MLLP exercises the same parser and gets the same acknowledgement. Unlike the socket, it
 * is behind the gateway and needs a token — which is the reason to prefer it where the far end can
 * manage one: MLLP has no authentication at all.
 *
 * <p>{@link Roles#HEALTH_INFORMATION_SHARE} on the inbound path, not a public route. An unauthenticated
 * endpoint that writes clinical messages into a hospital is the thing this platform spends the rest
 * of its security section avoiding, and the fact that the protocol traditionally has no auth is a
 * reason to add some rather than to match it.
 */
@RestController
@RequestMapping("/hl7")
public class Hl7Controller {

    private final Hl7IngestService ingest;
    private final Hl7OutboundBuilder builder;
    private final Hl7ExchangeRepository exchanges;
    private final Charset charset;
    private final int connectTimeout;
    private final int readTimeout;

    public Hl7Controller(Hl7IngestService ingest, Hl7OutboundBuilder builder,
                         Hl7ExchangeRepository exchanges,
                         @Value("${hms.interop.hl7.charset:UTF-8}") String charsetName,
                         @Value("${hms.interop.hl7.connect-timeout-ms:5000}") int connectTimeout,
                         @Value("${hms.interop.hl7.read-timeout-ms:15000}") int readTimeout) {
        this.ingest = ingest;
        this.builder = builder;
        this.exchanges = exchanges;
        this.charset = Charset.forName(charsetName);
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    /**
     * Receives one message and answers with the acknowledgement.
     *
     * <p>Always 200, whatever the message was. The acknowledgement carries the verdict — AA, AE or
     * AR — and that is the protocol's own error channel; answering 400 for a message the platform
     * refused would give a sender two contradictory error mechanisms and no rule for which wins.
     * A sender written against HL7 reads the MSA and nothing else.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    @PreAuthorize(Roles.HEALTH_INFORMATION_SHARE)
    public String receive(@Valid @RequestBody InteropDtos.Hl7InboundRequest request) {
        return ingest.receive(request.message(), Hl7Exchange.Transport.HTTP, "http");
    }

    /**
     * Builds a message and sends it over MLLP, recording both halves.
     *
     * <p>The exchange is recorded whether or not the send worked, and a rejection at the far end is
     * recorded as a failure of this exchange even though the socket did its job. A log that showed
     * "sent" for a message the receiver answered AR would be green while nothing arrived.
     */
    @PostMapping("/send")
    @PreAuthorize(Roles.HEALTH_INFORMATION_SHARE)
    public InteropDtos.Hl7ExchangeResponse send(@Valid @RequestBody InteropDtos.Hl7SendRequest request) {
        String message = switch (request.messageType()) {
            case "ADT^A04" -> builder.registration(request.patient(),
                    request.receivingApplication(), request.receivingFacility());
            case "ORU^R01" -> {
                if (request.order() == null) {
                    throw new BadRequestException(
                            "ORU^R01 carries results, so it needs an order; none was supplied");
                }
                yield builder.results(request.patient(), request.order(),
                        request.receivingApplication(), request.receivingFacility());
            }
            default -> throw new BadRequestException(
                    "This platform builds %s; it was asked for '%s'"
                            .formatted(builder.supported(), request.messageType()));
        };

        MllpClient.Result result = MllpClient.send(request.host(), request.port(), message,
                charset, connectTimeout, readTimeout);
        Hl7Exchange recorded = ingest.recordOutbound(message, Hl7Exchange.Transport.MLLP,
                "%s:%d".formatted(request.host(), request.port()),
                result.acknowledgement(), result.error());
        return toResponse(recorded);
    }

    /**
     * The message log, newest first.
     *
     * <p>{@code failuresOnly} is the parameter that makes this usable. An interface running for a
     * week has tens of thousands of accepted messages and a dozen that matter.
     */
    @GetMapping("/messages")
    @PreAuthorize(Roles.HEALTH_INFORMATION_SHARE)
    public PageResponse<InteropDtos.Hl7ExchangeResponse> messages(
            @RequestParam(defaultValue = "false") boolean failuresOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(
                exchanges.log(failuresOnly, PageRequest.of(page, Math.min(size, 100))),
                Hl7Controller::toResponse);
    }

    /** One exchange, with its raw text — the answer to "what did you actually receive". */
    @GetMapping("/messages/{id}")
    @PreAuthorize(Roles.HEALTH_INFORMATION_SHARE)
    public InteropDtos.Hl7ExchangeResponse message(@PathVariable UUID id) {
        return exchanges.findById(id)
                .map(Hl7Controller::toResponse)
                .orElseThrow(() -> com.hms.common.error.NotFoundException.of("Hl7Exchange", id));
    }

    /** Everything sent or received under one control id, which is how a sender asks. */
    @GetMapping("/messages/by-control-id/{controlId}")
    @PreAuthorize(Roles.HEALTH_INFORMATION_SHARE)
    public List<InteropDtos.Hl7ExchangeResponse> byControlId(@PathVariable String controlId) {
        return exchanges.findByControlIdOrderByReceivedAtDesc(controlId).stream()
                .map(Hl7Controller::toResponse)
                .toList();
    }

    private static InteropDtos.Hl7ExchangeResponse toResponse(Hl7Exchange exchange) {
        return new InteropDtos.Hl7ExchangeResponse(exchange.getId(),
                exchange.getDirection().name(), exchange.getMessageType(), exchange.getControlId(),
                exchange.getSendingApplication(), exchange.getSendingFacility(),
                exchange.getReceivingApplication(), exchange.getReceivingFacility(),
                exchange.getMessageAt(), exchange.getAckCode(), exchange.getAckText(),
                exchange.getError(), exchange.getTransport().name(), exchange.getPeer(),
                exchange.getReceivedAt(), exchange.getRaw(), exchange.getAckRaw());
    }
}
