package com.hms.scheduling.web.dto;

import com.hms.scheduling.domain.SchedulingEnums;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Order sets and care plans. */
public final class CareDtos {

    private CareDtos() {
    }

    // ---- order sets ----------------------------------------------------------

    public record OrderSetItemResponse(UUID id, SchedulingEnums.OrderSetKind kind, String code,
                                       String dose, String frequency, Integer durationDays,
                                       Integer quantity, String instructions, String priority,
                                       int displayOrder) {
    }

    public record OrderSetResponse(UUID id, String code, String name, String description,
                                   String departmentCode, boolean active,
                                   List<OrderSetItemResponse> items) {

        public OrderSetResponse {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * A line of a new set.
     *
     * @param kind LAB or MEDICATION, and it decides which of the other fields are required. A
     *             medication line needs a dose, a frequency, a duration and a quantity — the
     *             service refuses an incomplete one, and so does a CHECK constraint, because an
     *             order set is the one place a half-filled template is applied to a patient without
     *             anybody typing it.
     */
    public record OrderSetItemRequest(@NotNull SchedulingEnums.OrderSetKind kind,
                                      @NotBlank @Size(max = 32) String code,
                                      @Size(max = 48) String dose,
                                      @Size(max = 48) String frequency,
                                      @Min(1) Integer durationDays,
                                      @Min(1) Integer quantity,
                                      @Size(max = 500) String instructions,
                                      @Size(max = 16) String priority) {
    }

    public record CreateOrderSetRequest(@NotBlank @Size(max = 32) String code,
                                        @NotBlank @Size(max = 160) String name,
                                        @Size(max = 500) String description,
                                        @Size(max = 32) String departmentCode,
                                        @NotEmpty List<@Valid OrderSetItemRequest> items) {
    }

    public record UpdateOrderSetRequest(@Size(max = 160) String name, Boolean active) {
    }

    public record ApplyOrderSetRequest(@NotNull UUID encounterId,
                                       /*
                                        * Why a warning was accepted, forwarded to pharmacy-service
                                        * unchanged. Needed only when applying the set raises an
                                        * interaction the platform will let through with a reason;
                                        * the refusal says so, and the clinician re-applies with one.
                                        */
                                       @Size(max = 500) String overrideReason) {
    }

    /**
     * What applying a set actually did.
     *
     * <p>Both ids, in full, because this is a saga rather than a transaction: if something goes
     * wrong the clinician has to know precisely what exists. {@code compensated} says whether a
     * prescription raised a moment earlier had to be withdrawn again, and
     * {@code compensationFailed} is the one state that needs a person — the tests could not be
     * raised and the prescription could not be withdrawn either, so somebody has to cancel it by
     * hand rather than the platform pretending nothing happened.
     */
    public record ApplyOrderSetResponse(String orderSetCode, UUID labOrderId, UUID prescriptionId,
                                        List<String> raised, String message,
                                        boolean compensated, boolean compensationFailed) {

        public ApplyOrderSetResponse {
            raised = raised == null ? List.of() : List.copyOf(raised);
        }
    }

    // ---- care plans ----------------------------------------------------------

    public record GoalResponse(UUID id, String description, String problemCode,
                               LocalDate targetDate, SchedulingEnums.GoalStatus status,
                               String progressNote, String updatedBy, boolean overdue) {
    }

    public record CarePlanResponse(UUID id, UUID encounterId, UUID patientId, String patientMrn,
                                   String title, SchedulingEnums.CarePlanStatus status,
                                   String createdBy, Instant createdAt, Instant closedAt,
                                   List<GoalResponse> goals) {

        public CarePlanResponse {
            goals = goals == null ? List.of() : List.copyOf(goals);
        }
    }

    /**
     * @param problemCode one of the encounter's own diagnoses, or blank for a goal that belongs to
     *                    the admission rather than to one problem. Checked against the encounter,
     *                    so a plan cannot name a diagnosis nobody made.
     * @param targetDate  optional, because "before discharge" is a real target that no calendar
     *                    date expresses — and forcing one would produce dates nobody means.
     */
    public record AddGoalRequest(@NotBlank @Size(max = 500) String description,
                                 @Size(max = 16) String problemCode,
                                 LocalDate targetDate) {
    }

    public record CreateCarePlanRequest(@NotNull UUID encounterId,
                                        @NotBlank @Size(max = 160) String title,
                                        List<@Valid AddGoalRequest> goals) {
    }

    /**
     * @param progressNote required for any outcome other than OPEN or MET — enforced by a CHECK as
     *                     well as here. "Not met", with nothing else, is the shape of a record
     *                     nobody can learn from.
     */
    public record RecordGoalRequest(@NotNull SchedulingEnums.GoalStatus status,
                                    @Size(max = 1000) String progressNote) {
    }
}
