package com.hms.patient.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The facility directory against a real database, over the real HTTP surface.
 *
 * <p>The seeded building is asserted here on purpose. It is reference data, but it is the reference
 * data every other module is about to depend on — a booking validates against a room code, casualty
 * allocates a bed by room type, the queue display is keyed on a consulting room. If the seed drifts,
 * those break somewhere much less obvious than here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FacilityApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * A suffix unique to this JVM run.
     *
     * <p>These tests run against a persistent database and do not roll back, so a fixed code would
     * be a 409 on the second run — which is exactly how this suite first went red. Every code a
     * test creates carries it.
     */
    private static final String RUN = Long.toString(System.nanoTime(), 36)
            .toUpperCase(java.util.Locale.ROOT);

    private static String code(String prefix) {
        // room_types.code is varchar(24); keep the tail short enough to fit.
        String suffix = RUN.substring(Math.max(0, RUN.length() - 6));
        return (prefix + suffix).substring(0, Math.min(24, prefix.length() + suffix.length()));
    }

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        return jwt()
                .jwt(builder -> builder
                        .subject(UUID.randomUUID().toString())
                        .claim("preferred_username", "test-user")
                        .claim("roles", List.of(roles)))
                .authorities(authorities);
    }

    // ---- the seeded building --------------------------------------------------

    @Test
    @DisplayName("the ground floor and first floor are seeded, ordered bottom-up")
    void floorsAreSeeded() throws Exception {
        String body = mockMvc.perform(get("/floors").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode floors = objectMapper.readTree(body);
        assertThat(floors).hasSizeGreaterThanOrEqualTo(2);
        // Bottom-up, the way a lift panel reads.
        assertThat(floors.get(0).get("level").asInt()).isEqualTo(0);
        assertThat(floors.get(0).get("name").asString()).isEqualTo("Ground Floor");
    }

    @Test
    @DisplayName("a consulting room carries its floor, clinic, dimensions and wayfinding text")
    void consultationRoomIsFullyDescribed() throws Exception {
        mockMvc.perform(get("/rooms/GF-GEN").with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("General OPD"))
                .andExpect(jsonPath("$.roomTypeCode").value("CONSULTATION"))
                .andExpect(jsonPath("$.roomTypeName").value("Consulting room"))
                .andExpect(jsonPath("$.schedulable").value(true))
                .andExpect(jsonPath("$.clinical").value(true))
                .andExpect(jsonPath("$.floorName").value("Ground Floor"))
                .andExpect(jsonPath("$.departmentCode").value("GEN"))
                .andExpect(jsonPath("$.bookable").value(true))
                // The one field a patient-facing view cannot do without.
                .andExpect(jsonPath("$.directions").value(
                        org.hamcrest.Matchers.containsString("reception")));
    }

    @Test
    @DisplayName("as-drawn dimensions render in feet and inches, not decimal feet")
    void dimensionsReadLikeTheDrawing() throws Exception {
        // 15.50 x 8.13 decimal feet. Whoever is holding the plan is looking for 15'6".
        mockMvc.perform(get("/rooms/GF-GEN").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions").value("15'6\" x 8'2\""));

        // A whole number of feet prints without an inches part.
        mockMvc.perform(get("/rooms/GF-RMO").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions").value("7'6\" x 9'"));

        // An unrecorded dimension yields null rather than half a measurement, which would read as
        // a complete one.
        mockMvc.perform(get("/rooms/GF-PAED").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions").doesNotExist());
    }

    @Test
    @DisplayName("casualty is seeded with its bed positions")
    void casualtyHasBeds() throws Exception {
        mockMvc.perform(get("/rooms/GF-CAS").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomTypeCode").value("EMERGENCY_BAY"))
                .andExpect(jsonPath("$.bedAllocated").value(true))
                .andExpect(jsonPath("$.schedulable").value(false))
                .andExpect(jsonPath("$.capacity").value(6))
                .andExpect(jsonPath("$.bedCount").value(6))
                // Clinical, but never on a calendar: arrivals are unscheduled.
                .andExpect(jsonPath("$.bookable").value(false));

        String body = mockMvc.perform(get("/beds")
                        .param("type", "EMERGENCY_BAY")
                        .param("type", "EMERGENCY_ROOM")
                        .with(as("NURSE")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Six bay positions plus the two in the enclosed room. This is how admissions-service asks
        // for "the casualty beds" without knowing which rooms make up casualty.
        assertThat(objectMapper.readTree(body)).hasSize(8);
    }

    @Test
    @DisplayName("non-clinical rooms patients ask about are in the directory")
    void nonClinicalRoomsAreListed() throws Exception {
        // A directory that omits these cannot answer what people actually ask at a front desk.
        for (String code : List.of("GF-PHR", "GF-RCP", "F1-POO", "F1-MED", "F1-FAM")) {
            mockMvc.perform(get("/rooms/{code}", code).with(as("RECEPTIONIST")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(code));
        }
    }

    // ---- booking eligibility --------------------------------------------------

    @Test
    @DisplayName("only consulting and procedure rooms are bookable")
    void bookableRoomsAreClinicAndProcedureOnly() throws Exception {
        String body = mockMvc.perform(get("/rooms/bookable").with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> codes = objectMapper.readTree(body).valueStream()
                .map(node -> node.get("code").asString()).toList();

        assertThat(codes).contains("GF-GEN", "GF-PAED", "GF-OBG", "GF-MAS", "GF-MPR");
        // A booking must never be able to land in a casualty bay, an in-patient suite, or a
        // corridor.
        assertThat(codes)
                .as("bed-allocated and non-clinical space must never be bookable")
                .doesNotContain("GF-CAS", "GF-CASR", "F1-MST", "F1-KID",
                        "GF-LOB", "GF-RCP", "GF-PHR", "F1-POO");
    }

    @Test
    @DisplayName("the bookable list narrows to one clinic")
    void bookableRoomsFilterByDepartment() throws Exception {
        String body = mockMvc.perform(get("/rooms/bookable").param("department", "PAED")
                        .with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> codes = objectMapper.readTree(body).valueStream()
                .map(node -> node.get("code").asString()).toList();
        assertThat(codes).containsExactly("GF-PAED");
    }

    @Test
    @DisplayName("a department filter does not sweep up rooms with no department")
    void departmentFilterDoesNotMatchUnassignedRooms() throws Exception {
        // The regression this guards: an outer join leaves department null for every non-clinical
        // room, so a predicate of "... or department is null" made a lobby and a plant room match
        // a filter for the gynaecology clinic. The same defect shipped in the identity user-role
        // filter and in the staff search.
        String body = mockMvc.perform(get("/rooms").param("department", "OBG").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode page = objectMapper.readTree(body);
        assertThat(page.get("totalElements").asInt()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("code").asString()).isEqualTo("GF-OBG");
    }

    @Test
    @DisplayName("the location shape carries exactly what scheduling needs and nothing more")
    void locationShapeIsNarrow() throws Exception {
        String body = mockMvc.perform(get("/rooms/GF-OBG/location").with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("GF-OBG"))
                .andExpect(jsonPath("$.floorName").value("Ground Floor"))
                .andExpect(jsonPath("$.bookable").value(true))
                .andReturn().getResponse().getContentAsString();

        // Narrow on purpose: this is the cross-service contract, and a field added here becomes a
        // field scheduling can cache and let go stale.
        assertThat(objectMapper.readTree(body).propertyNames())
                .containsExactlyInAnyOrder("id", "code", "name", "floorName", "directions", "bookable");
    }

    // ---- validation -----------------------------------------------------------

    @Test
    @DisplayName("space allocated by bed cannot be marked bookable")
    void bedAllocatedSpaceCannotBeBooked() throws Exception {
        // Letting a casualty bay onto a calendar would put a scheduled outpatient in a
        // resuscitation bay.
        for (String type : List.of("EMERGENCY_BAY", "EMERGENCY_ROOM", "WARD", "SUITE")) {
            mockMvc.perform(post("/rooms").with(as("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "code", code("T" + type.charAt(0)),
                                    "name", "Test " + type,
                                    "roomTypeCode", type,
                                    "floorCode", "GF",
                                    "capacity", 1,
                                    "bookable", true))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("a non-clinical room cannot be booked or given a bed capacity")
    void nonClinicalRoomsAreConstrained() throws Exception {
        mockMvc.perform(post("/rooms").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code("TC"), "name", "Test Corridor",
                                "roomTypeCode", "CIRCULATION", "floorCode", "GF", "bookable", true))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/rooms").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code("TS"), "name", "Test Store",
                                "roomTypeCode", "SUPPORT", "floorCode", "GF", "capacity", 3))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a room on a floor that does not exist is refused")
    void unknownFloorIsRefused() throws Exception {
        mockMvc.perform(post("/rooms").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code("TF"), "name", "Nowhere",
                                "roomTypeCode", "CONSULTATION", "floorCode", "F99"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a duplicate room code is a conflict, and codes are case-insensitive")
    void duplicateRoomCodeIsRefused() throws Exception {
        mockMvc.perform(post("/rooms").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "gf-gen", "name", "Duplicate",
                                "roomTypeCode", "CONSULTATION", "floorCode", "GF"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("beds cannot exceed the room's designed capacity")
    void bedCapacityIsEnforced() throws Exception {
        // A bay with more beds recorded than it has positions means one of them is somewhere else.
        mockMvc.perform(post("/rooms/GF-CAS/beds").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "CAS-99"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a bed cannot be added to a non-clinical room")
    void bedsOnlyGoInClinicalRooms() throws Exception {
        mockMvc.perform(post("/rooms/GF-LOB/beds").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "LOB-1"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown room code is a 404, not an empty 200")
    void unknownRoomIs404() throws Exception {
        mockMvc.perform(get("/rooms/NO-SUCH-ROOM").with(as("DOCTOR")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/rooms/NO-SUCH-ROOM/location").with(as("DOCTOR")))
                .andExpect(status().isNotFound());
    }

    // ---- the taxonomy is configuration ----------------------------------------

    @Test
    @DisplayName("the room-type vocabulary is data, ordered for a pick-list")
    void roomTypesAreData() throws Exception {
        String body = mockMvc.perform(get("/room-types").with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode types = objectMapper.readTree(body);
        assertThat(types).hasSizeGreaterThanOrEqualTo(14);
        // display_order puts the clinical types first, so a pick-list reads sensibly without the
        // UI knowing anything about the taxonomy.
        assertThat(types.get(0).get("code").asString()).isEqualTo("CONSULTATION");

        Map<String, JsonNode> byCode = new java.util.HashMap<>();
        types.valueStream().forEach(node -> byCode.put(node.get("code").asString(), node));

        // The three flags, on the types where each matters.
        assertThat(byCode.get("CONSULTATION").get("schedulable").asBoolean()).isTrue();
        assertThat(byCode.get("EMERGENCY_BAY").get("clinical").asBoolean()).isTrue();
        assertThat(byCode.get("EMERGENCY_BAY").get("bedAllocated").asBoolean()).isTrue();
        assertThat(byCode.get("EMERGENCY_BAY").get("schedulable").asBoolean()).isFalse();
        assertThat(byCode.get("CIRCULATION").get("clinical").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a new room type needs no code change: add it, then use it")
    void aNewRoomTypeWorksImmediately() throws Exception {
        // This is what the redesign bought. A hospital with a dialysis unit adds a row and every
        // downstream filter, picker and validation rule honours it - no enum constant, no
        // recompile, no migration to widen a CHECK constraint.
        String dialysis = code("DIALYSIS");
        String room = code("D");
        mockMvc.perform(post("/room-types").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", dialysis, "name", "Dialysis unit",
                                "description", "Haemodialysis stations",
                                "clinical", true, "bedAllocated", true, "schedulable", false,
                                "displayOrder", 45))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(dialysis));

        // A room of the brand-new type is accepted...
        mockMvc.perform(post("/rooms").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", room, "name", "Dialysis Unit",
                                "roomTypeCode", dialysis, "floorCode", "GF", "capacity", 4))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomTypeCode").value(dialysis))
                .andExpect(jsonPath("$.bedAllocated").value(true));

        // ...and the validation rule that used to name four hard-coded types now applies to it,
        // because it reads the flag rather than matching a constant.
        mockMvc.perform(post("/rooms").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code("D2"), "name", "Dialysis Two",
                                "roomTypeCode", dialysis, "floorCode", "GF",
                                "capacity", 2, "bookable", true))))
                .andExpect(status().isBadRequest());

        // And beds go in it, because the type says it is clinical.
        mockMvc.perform(post("/rooms/{code}/beds", room).with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("code", "DIA-1", "label", "Station 1"))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a room type may be made schedulable later, and rooms of it become bookable")
    void reconfiguringATypeChangesBehaviour() throws Exception {
        String physio = code("PHYSIO");
        String room = code("P");
        mockMvc.perform(post("/room-types").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", physio, "name", "Physiotherapy",
                                "clinical", true, "schedulable", false))))
                .andExpect(status().isCreated());

        // Not schedulable yet, so a bookable room of it is refused.
        mockMvc.perform(post("/rooms").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", room, "name", "Physiotherapy Room",
                                "roomTypeCode", physio, "floorCode", "GF", "bookable", true))))
                .andExpect(status().isBadRequest());

        // Reconfigure the type...
        mockMvc.perform(patch("/room-types/{code}", physio).with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("schedulable", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulable").value(true));

        // ...and the same request now succeeds. No deployment in between.
        mockMvc.perform(post("/rooms").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", room, "name", "Physiotherapy Room",
                                "roomTypeCode", physio, "floorCode", "GF", "bookable", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookableNow").value(true));
    }

    @Test
    @DisplayName("a room type combination that would misbehave is refused by the database")
    void impossibleTypeCombinationsAreRefused() throws Exception {
        // Schedulable-and-bed-allocated would let a booked outpatient be sent to a resuscitation
        // position. Schedulable-but-not-clinical would put one in a corridor. Both are CHECK
        // constraints on room_types, so no amount of misconfiguration reaches the booking path.
        mockMvc.perform(post("/room-types").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code("BAD1"), "name", "Bookable ward",
                                "clinical", true, "bedAllocated", true, "schedulable", true))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/room-types").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code("BAD2"), "name", "Bookable corridor",
                                "clinical", false, "schedulable", true))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a room referencing an unknown type is refused, and says where types come from")
    void unknownRoomTypeIsRefused() throws Exception {
        mockMvc.perform(post("/rooms").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code("U"), "name", "Unknown type",
                                "roomTypeCode", "NO_SUCH_TYPE", "floorCode", "GF"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("room-types")));
    }

    @Test
    @DisplayName("only an administrator changes the taxonomy")
    void onlyAdminChangesTheTaxonomy() throws Exception {
        for (String role : List.of("DOCTOR", "NURSE", "RECEPTIONIST", "LAB_TECH", "PATHOLOGIST")) {
            mockMvc.perform(post("/room-types").with(as(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "code", code("SNEAKY"), "name", "Sneaky", "clinical", true))))
                    .andExpect(status().isForbidden());
        }
        // But everyone can read it - the UI needs it to render a filter.
        mockMvc.perform(get("/room-types").with(as("LAB_TECH"))).andExpect(status().isOk());
    }

    // ---- authorization --------------------------------------------------------

    @ParameterizedTest(name = "{0} cannot write to {1}")
    @CsvSource({
            "DOCTOR,       /rooms",
            "NURSE,        /rooms",
            "RECEPTIONIST, /rooms",
            "LAB_TECH,     /rooms",
            "PATHOLOGIST,  /rooms",
            "DOCTOR,       /floors",
            "RECEPTIONIST, /floors",
    })
    void onlyAdminChangesTheBuilding(String role, String path) throws Exception {
        // A valid body, so authorization is what is being tested rather than bean validation -
        // argument resolution runs before method security, so an empty body would give 400 and
        // prove nothing.
        String body = path.trim().equals("/rooms")
                ? objectMapper.writeValueAsString(Map.of("code", code("TA"), "name", "Sneaky",
                        "roomTypeCode", "CONSULTATION", "floorCode", "GF"))
                : objectMapper.writeValueAsString(Map.of("code", "F9", "name", "Ninth", "level", 9));

        mockMvc.perform(post(path.trim()).with(as(role.trim()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("every role may read the directory")
    void everyRoleCanReadTheDirectory() throws Exception {
        // Knowing where the pharmacy is, is not privileged information.
        for (String role : List.of("ADMIN", "DOCTOR", "NURSE", "RECEPTIONIST",
                "LAB_TECH", "PATHOLOGIST")) {
            mockMvc.perform(get("/rooms/directory").with(as(role)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("the directory is unreachable without a token")
    void directoryRequiresAToken() throws Exception {
        mockMvc.perform(get("/rooms/directory")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/rooms/GF-GEN")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/beds")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the directory groups every active room under its floor")
    void directoryGroupsByFloor() throws Exception {
        String body = mockMvc.perform(get("/rooms/directory").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode directory = objectMapper.readTree(body);
        int rooms = directory.valueStream().mapToInt(node -> node.get("rooms").size()).sum();
        assertThat(rooms)
                .as("every seeded room appears exactly once under a floor")
                .isGreaterThanOrEqualTo(21);
    }

    @Test
    @DisplayName("a bed can be relabelled and taken out of service, and stops being allocatable")
    void aBedCanBeDecommissioned() throws Exception {
        // A bed could be added and never removed: a position taken out of service had to be
        // deleted by hand or left looking allocatable to whatever allocates beds next.
        // Its own WARD room. GF-CAS is seeded at exactly its designed capacity, so adding a bed
        // there is the 409 another test asserts - and a bed of a casualty type would change the
        // count that casualtyHasBeds pins.
        String roomCode = code("WD");
        mockMvc.perform(post("/rooms").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", roomCode, "name", "Decommission Test Ward",
                                "roomTypeCode", "WARD", "floorCode", "GF", "capacity", 2))))
                .andExpect(status().isCreated());

        String bedCode = code("BD");
        String created = mockMvc.perform(post("/rooms/" + roomCode + "/beds").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", bedCode, "label", "Temporary"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String bedId = objectMapper.readTree(created).get("id").asString();

        mockMvc.perform(patch("/beds/" + bedId).with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "label", "Bay 9, screened", "active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Bay 9, screened"))
                .andExpect(jsonPath("$.active").value(false));

        // Out of service means out of the list bed allocation reads, and the row is still there.
        String beds = mockMvc.perform(get("/beds").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(beds).doesNotContain(bedCode);

        // And the capacity it was occupying is released, which is the point of deactivating
        // rather than leaving it: the room can take a replacement position.
        mockMvc.perform(patch("/beds/" + bedId).with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("only an administrator may decommission a bed")
    void bedUpdatesAreAdminOnly() throws Exception {
        mockMvc.perform(patch("/beds/" + UUID.randomUUID()).with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("two floors cannot share a level, and the refusal names what is already there")
    void oneFloorPerLevel() throws Exception {
        // uq_floor_level enforces it. Without a check in the service the violation surfaced as the
        // generic "conflicts with existing data or a database constraint" - true, and no help at
        // all to somebody who has just been told their code was fine.
        mockMvc.perform(post("/floors").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code("L").substring(0, 6), "name", "Clashing Floor", "level", 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("Level 0 is already Ground Floor")));
    }

    /**
     * The lowest level no floor occupies.
     *
     * <p>`uq_floor_level` is a hard unique constraint and there is no way to remove a floor, so a
     * test that hard-codes a level passes once against a persistent database and 409s for ever
     * after. Claiming the next free one is the only repeatable shape.
     */
    private short nextFreeLevel() throws Exception {
        JsonNode floors = objectMapper.readTree(
                mockMvc.perform(get("/floors").param("includeInactive", "true").with(as("ADMIN")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        int highest = 0;
        for (JsonNode floor : floors) {
            highest = Math.max(highest, floor.get("level").asInt());
        }
        return (short) (highest + 1);
    }

    @Test
    @DisplayName("a floor may be saved at the level it already holds")
    void aFloorDoesNotConflictWithItself() throws Exception {
        short level = nextFreeLevel();
        String created = mockMvc.perform(post("/floors").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code("S").substring(0, 6), "name", "Self Floor",
                                "level", level))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asString();

        // Renaming it re-sends the same level. Checking the level without excluding the row being
        // updated would make every such save a conflict with itself.
        mockMvc.perform(patch("/floors/" + id).with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Self Floor, renamed", "level", level))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Self Floor, renamed"));
    }

    @Test
    @DisplayName("a room with no clinic is still found by search, and still not by a clinic filter")
    void roomsWithoutADepartmentAreSearchable() throws Exception {
        // Both halves matter and they pull in opposite directions. Written as the JPQL path
        // r.department.code the predicate became an inner join, so every room with no clinic was
        // dropped from this search whatever was filtered - a lobby, a corridor, the pharmacy, a
        // ward. The obvious repair, "or r.department is null", swings the other way and makes a
        // lobby match a filter for the paediatric clinic.
        String roomCode = code("NC");
        mockMvc.perform(post("/rooms").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", roomCode, "name", "Clinicless Store",
                                "roomTypeCode", "SUPPORT", "floorCode", "GF"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.departmentCode").value(org.hamcrest.Matchers.nullValue()));

        assertThat(mockMvc.perform(get("/rooms").param("q", roomCode).with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .as("a room with no clinic must be findable")
                .contains(roomCode);

        assertThat(mockMvc.perform(get("/rooms")
                        .param("q", roomCode).param("department", "CARD")
                        .with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .as("but it belongs to no clinic, so a clinic filter must not sweep it up")
                .doesNotContain(roomCode);
    }

    @Test
    @DisplayName("a closed floor and a retired room type are still visible to an administrator")
    void retiredMasterDataIsStillReachable() throws Exception {
        // Both endpoints took the flag from the query string and threw it away, so deactivating
        // either was a one-way door: the row vanished from the only screen that could bring it
        // back. Floors had a sharper edge - uq_floor_level counts a closed floor too, so its level
        // looked free, the create was refused, and nothing on any screen said what held it.
        short level = nextFreeLevel();
        String floorCode = code("R").substring(0, 6);
        String created = mockMvc.perform(post("/floors").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", floorCode, "name", "Retired Floor", "level", level))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(patch("/floors/" + objectMapper.readTree(created).get("id").asString())
                        .with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isOk());

        assertThat(mockMvc.perform(get("/floors").with(as("ADMIN")))
                .andReturn().getResponse().getContentAsString())
                .as("a closed floor is not offered as a place to put a room")
                .doesNotContain(floorCode);
        assertThat(mockMvc.perform(get("/floors").param("includeInactive", "true").with(as("ADMIN")))
                .andReturn().getResponse().getContentAsString())
                .as("but it is visible where it can be reopened")
                .contains(floorCode);

        String typeCode = code("RT");
        mockMvc.perform(post("/room-types").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", typeCode, "name", "Retired Type"))))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/room-types/" + typeCode).with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isOk());

        assertThat(mockMvc.perform(get("/room-types").with(as("RECEPTIONIST")))
                .andReturn().getResponse().getContentAsString())
                .doesNotContain(typeCode);
        assertThat(mockMvc.perform(get("/room-types").param("includeInactive", "true").with(as("ADMIN")))
                .andReturn().getResponse().getContentAsString())
                .contains(typeCode);
    }
}
