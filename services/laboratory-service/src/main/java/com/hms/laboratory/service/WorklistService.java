package com.hms.laboratory.service;

import com.hms.common.error.NotFoundException;
import com.hms.laboratory.device.astm.AstmQueryReader;
import com.hms.laboratory.device.astm.AstmWorklistWriter;
import com.hms.laboratory.domain.LabEnums;
import com.hms.laboratory.domain.LabOrder;
import com.hms.laboratory.domain.LabOrderItem;
import com.hms.laboratory.domain.Specimen;
import com.hms.laboratory.web.dto.LabDtos;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "what is ordered for this sample?" — the host-query direction of analyzer communication.
 *
 * <p>Until now the link was one-way: an analyzer pushed results at
 * {@code POST /lab/device-messages} and the platform matched them to an order by accession number.
 * The worklist had to be keyed into the instrument by hand, which is the second common source of a
 * misfiled result — the first being the tube itself, which the barcode label now addresses.
 *
 * <p>Protocol-agnostic on purpose. The lookup and the eligibility rule live here; ASTM appears only
 * in {@link #astmReply}, which adapts this answer onto the wire. A second protocol is a second
 * adapter, not a second copy of the rule.
 */
@Service
public class WorklistService {

    private static final Logger log = LoggerFactory.getLogger(WorklistService.class);

    /**
     * The statuses an analyzer may be told to run.
     *
     * <p>{@code CANCELLED} is excluded because running a cancelled test burns reagent and produces a
     * result nobody asked for, which then has to be explained away on a chart. {@code VERIFIED} is
     * excluded because the order is signed off and closed; a genuine repeat is a new order, which is
     * also the only way it gets its own audit trail.
     */
    private static final Set<LabEnums.OrderStatus> RUNNABLE = EnumSet.of(
            LabEnums.OrderStatus.ORDERED,
            LabEnums.OrderStatus.COLLECTED,
            LabEnums.OrderStatus.IN_PROGRESS,
            LabEnums.OrderStatus.RESULTED);

    private final LabOrderService orders;
    private final String senderName;

    public WorklistService(LabOrderService orders,
                           @Value("${hms.laboratory.astm.sender-name:MEDSYNC}") String senderName) {
        this.orders = orders;
        this.senderName = senderName;
    }

    /**
     * The worklist for one accession number, for a human or a UI.
     *
     * @throws NotFoundException if no specimen carries that accession. Deliberate: a person asking
     *                           about a tube that does not exist needs to be told so.
     */
    @Transactional(readOnly = true)
    public LabDtos.WorklistResponse forSample(String accessionNo) {
        return lookup(accessionNo).orElseThrow(() ->
                new NotFoundException("No specimen with accession '"
                        + (accessionNo == null ? "" : accessionNo.trim()) + "'"));
    }

    /**
     * The same question asked by an instrument, answered on the wire.
     *
     * <p><strong>The failure behaviour is deliberately the opposite of {@link #forSample}.</strong> A
     * query for an unknown or closed sample returns a well-formed transmission with no orders in it,
     * never an error. An analyzer is a state machine waiting on a reply: a 404, or silence, leaves it
     * blocked mid-conversation, and the operator sees an instrument that has hung rather than a tube
     * with nothing ordered. "No orders" is an answer; nothing is not.
     *
     * @param queryTransmission the raw ASTM query the instrument sent
     * @return the raw ASTM worklist to send back
     */
    @Transactional(readOnly = true)
    public String astmReply(String queryTransmission) {
        List<String> sampleIds = AstmQueryReader.sampleIdsIn(queryTransmission);
        List<AstmWorklistWriter.Entry> entries = new ArrayList<>(sampleIds.size());

        for (String sampleId : sampleIds) {
            Optional<LabDtos.WorklistResponse> worklist = findRunnable(sampleId);
            if (worklist.isEmpty()) {
                // Logged, not raised. The instrument gets a valid empty answer; the bench gets a
                // line to look at when a tube unexpectedly does not run.
                log.info("Analyzer queried sample {} — nothing runnable to send", sampleId);
                continue;
            }
            LabDtos.WorklistResponse view = worklist.get();
            entries.add(new AstmWorklistWriter.Entry(view.accessionNo(), view.patientSex(),
                    view.priority(), view.specimenType(), view.testCodes()));
        }
        return AstmWorklistWriter.write(entries, senderName, Instant.now());
    }

    /**
     * Present and runnable, or empty. Used by the machine-facing path, which must not throw.
     *
     * <p>Not implemented by catching the exception {@link #forSample} raises. An exception thrown out
     * of a transactional method marks that transaction rollback-only, so catching it here would
     * still fail at commit with an {@code UnexpectedRollbackException} - a 500 for the analyzer that
     * this method exists specifically to avoid. Both paths share a non-throwing lookup instead, and
     * only the human-facing one turns absence into an error.
     */
    @Transactional(readOnly = true)
    public Optional<LabDtos.WorklistResponse> findRunnable(String accessionNo) {
        return lookup(accessionNo).filter(LabDtos.WorklistResponse::runnable);
    }

    private Optional<LabDtos.WorklistResponse> lookup(String accessionNo) {
        return orders.findSpecimen(accessionNo)
                .map(specimen -> toWorklist(specimen, orders.requireDetail(specimen.getOrder().getId())));
    }

    private LabDtos.WorklistResponse toWorklist(Specimen specimen, LabOrder order) {
        List<String> testCodes = order.getItems().stream().map(LabOrderItem::getTestCode).toList();
        return new LabDtos.WorklistResponse(
                specimen.getAccessionNo(),
                order.getId(),
                order.getPatientMrn(),
                order.getPatientSex(),
                order.getPriority().name(),
                order.getStatus().name(),
                specimen.getSpecimenType(),
                testCodes,
                RUNNABLE.contains(order.getStatus()));
    }
}
