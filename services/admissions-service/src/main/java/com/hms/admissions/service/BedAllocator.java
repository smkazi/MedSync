package com.hms.admissions.service;

import com.hms.admissions.client.BedDirectoryClient;
import com.hms.admissions.domain.AdmissionEnums;
import com.hms.admissions.domain.BedOccupancy;
import com.hms.admissions.repo.BedOccupancyRepository;
import com.hms.common.error.ConflictException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Putting a patient in a bed, and taking them out of it.
 *
 * <p>Every path that touches occupancy goes through here — casualty placement, admission,
 * transfer, discharge — because one bed holding two patients is the failure this service exists to
 * make impossible, and one door is easier to guard than four.
 *
 * <p><strong>The insert is the check.</strong> There is a "is this bed free?" query on the
 * repository and it is for rendering, not for allocation: two clinicians allocating the last bed at
 * the same instant both see it free, and the one that loses the partial unique index is the one who
 * gets told. Reading first and then writing cannot be made safe by care.
 */
@Service
public class BedAllocator {

    private static final Logger log = LoggerFactory.getLogger(BedAllocator.class);

    private final BedOccupancyRepository occupancy;
    private final BedDirectoryClient beds;

    public BedAllocator(BedOccupancyRepository occupancy, BedDirectoryClient beds) {
        this.occupancy = occupancy;
        this.beds = beds;
    }

    /**
     * Claims a bed for an occupant.
     *
     * <p>Verifies the bed with the facility directory first, which fails closed: allocating a bed
     * this service could not confirm means sending a patient to a space that may not exist or may
     * not be a clinical one.
     *
     * @throws ConflictException when somebody else got the bed. The message names the bed, because
     *                          "conflict" tells a nurse holding a trolley nothing.
     */
    @Transactional
    public BedOccupancy claim(UUID bedId, List<String> allowedTypes,
                              AdmissionEnums.OccupantType occupantType, UUID occupantId,
                              String bearerToken) {
        BedDirectoryClient.Bed bed = beds.require(bedId, allowedTypes, bearerToken);
        try {
            return occupancy.saveAndFlush(new BedOccupancy(bed.id(), bed.code(), bed.roomCode(),
                    occupantType, occupantId));
        } catch (DataIntegrityViolationException ex) {
            // The partial unique index fired, which means somebody else claimed this bed between
            // the directory lookup and the insert. That is the control working, so it is a 409
            // with the bed named rather than a 500.
            log.info("Bed {} was already occupied when {} {} tried to claim it",
                    bed.code(), occupantType, occupantId);
            throw new ConflictException(("Bed %s in %s has just been taken. Pick another from the "
                    + "free list.").formatted(bed.code(), bed.roomCode()));
        }
    }

    /** Frees whatever bed this occupant is in. Silent when they are in none — discharge twice. */
    @Transactional
    public void release(AdmissionEnums.OccupantType occupantType, UUID occupantId) {
        occupancy.findByOccupantTypeAndOccupantIdAndReleasedAtIsNull(occupantType, occupantId)
                .ifPresent(BedOccupancy::release);
    }

    /**
     * Moves an occupant from one bed to another, in one transaction.
     *
     * <p>Release then claim, and the order is deliberate: claiming first would need both beds free
     * at once and would fail a move into the last bed on the ward. Releasing first means there is
     * a moment inside the transaction when the patient is in no bed — which is invisible outside
     * it, and is the honest half of the trade. What must never happen is the other order's
     * failure: a window in which the patient appears to be in two beds, which a census would
     * count twice.
     *
     * @throws ConflictException when the destination has just been taken. The whole transaction
     *                          rolls back, so the patient stays where they were rather than ending
     *                          up in no bed at all.
     */
    @Transactional
    public BedOccupancy move(AdmissionEnums.OccupantType occupantType, UUID occupantId,
                             UUID toBedId, List<String> allowedTypes, String bearerToken) {
        release(occupantType, occupantId);
        // Flushed by claim's saveAndFlush; without the release being visible first, the index
        // would refuse a move into the bed the patient is already in.
        occupancy.flush();
        return claim(toBedId, allowedTypes, occupantType, occupantId, bearerToken);
    }

    /** Who is in this bed now, for rendering a bed map. Never for deciding an allocation. */
    @Transactional(readOnly = true)
    public Optional<BedOccupancy> currentOccupantOf(UUID bedId) {
        return occupancy.findByBedIdAndReleasedAtIsNull(bedId);
    }

    @Transactional(readOnly = true)
    public List<BedOccupancy> allCurrent() {
        return occupancy.findByReleasedAtIsNull();
    }
}
