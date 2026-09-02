package com.hms.admissions.service;

import com.hms.admissions.client.BedDirectoryClient;
import com.hms.admissions.domain.BedOccupancy;
import com.hms.admissions.web.dto.AdmissionDtos;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The bed map: every bed of a given kind, and whether anybody is in it.
 *
 * <p>Composed here rather than served by patient-service, because the two halves belong to
 * different owners. The facility directory knows what beds exist — that is master data, and it
 * deliberately keeps no occupancy flag, because a flag written by one service and maintained by
 * another is a flag that goes stale. This service knows who is in them. Joining the two at read
 * time means neither has to trust the other's copy.
 */
@Service
public class BedBoardService {

    private final BedDirectoryClient beds;
    private final BedAllocator allocator;

    public BedBoardService(BedDirectoryClient beds, BedAllocator allocator) {
        this.beds = beds;
        this.allocator = allocator;
    }

    /** Casualty's bays, occupied and free. */
    public List<AdmissionDtos.BedStateResponse> casualtyBeds(String bearerToken) {
        return board(BedDirectoryClient.CASUALTY_TYPES, bearerToken);
    }

    /** The wards' beds, occupied and free. */
    public List<AdmissionDtos.BedStateResponse> inpatientBeds(String bearerToken) {
        return board(BedDirectoryClient.INPATIENT_TYPES, bearerToken);
    }

    private List<AdmissionDtos.BedStateResponse> board(List<String> types, String bearerToken) {
        Map<UUID, BedOccupancy> current = allocator.allCurrent().stream()
                .collect(Collectors.toMap(BedOccupancy::getBedId, Function.identity(),
                        // Cannot happen — the partial unique index makes two current rows for one
                        // bed unrepresentable. Keeping the first rather than throwing means a
                        // database somebody has hand-edited renders a bed map instead of a 500.
                        (first, second) -> first));

        return beds.bedsOfTypes(types, bearerToken).stream()
                .map(bed -> {
                    BedOccupancy occupancy = current.get(bed.id());
                    return new AdmissionDtos.BedStateResponse(bed.id(), bed.code(), bed.label(),
                            bed.roomCode(), bed.roomName(), bed.floorName(),
                            occupancy != null,
                            occupancy == null ? null : occupancy.getOccupantType(),
                            occupancy == null ? null : occupancy.getOccupantId(),
                            occupancy == null ? null : occupancy.getSince());
                })
                .toList();
    }
}
