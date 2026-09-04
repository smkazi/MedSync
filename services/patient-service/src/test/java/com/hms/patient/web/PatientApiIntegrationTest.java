package com.hms.patient.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.patient.domain.Patient;
import com.hms.patient.domain.Sex;
import com.hms.patient.repo.PatientRepository;
import java.time.LocalDate;
import com.hms.patient.web.dto.PatientDtos;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Covers the patient API against a real database.
 *
 * <p>Callers are authenticated with the {@code jwt()} post-processor rather than a live
 * identity-service: this service is a stateless resource server, so a request carries a bearer
 * token and nothing else. What is under test here is this service's authorisation rules and
 * persistence; token minting is covered by identity-service's own suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PatientApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PatientRepository patients;

    @Autowired
    private DataSource dataSource;

    /** A caller holding the given roles, shaped exactly like a token identity-service would mint. */
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

    /** A unique surname per test, so tests never collide over duplicate detection. */
    private String uniqueSurname() {
        return "Surname" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Map<String, Object> validRegistration(String surname) {
        return Map.of("firstName", "Meera", "lastName", surname, "dateOfBirth", "1988-04-12",
                "sex", "FEMALE", "phone", "+91 98200 11223", "nationalId", "ABCDE1234F",
                "insurancePolicyNo", "SH-99881");
    }

    private MockHttpServletRequestBuilder registrationRequest(Map<String, Object> body, String... roles)
            throws Exception {
        return post("/patients").with(as(roles))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private JsonNode register(String surname) throws Exception {
        String body = mockMvc.perform(registrationRequest(validRegistration(surname), "RECEPTIONIST"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private void recordAllergy(String patientId, String substance, String severity) throws Exception {
        mockMvc.perform(post("/patients/" + patientId + "/allergies").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("substance", substance, "severity", severity))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("an anonymous request is rejected")
    void anonymousAccessIsRejected() throws Exception {
        mockMvc.perform(get("/patients")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("registration issues an MRN and never echoes the encrypted identifiers")
    void registrationIssuesMrnAndHidesIdentifiers() throws Exception {
        JsonNode created = register(uniqueSurname());

        assertThat(created.get("mrn").asString()).matches("MRN-\\d{4}-\\d{6}");
        assertThat(created.has("nationalId"))
                .as("the encrypted national id must not appear in an ordinary patient response")
                .isFalse();
        assertThat(created.has("insurancePolicyNo")).isFalse();
    }

    @Test
    @DisplayName("an ABHA is linked after registration, normalised, and never echoed")
    void abhaIsLinkedAndHidden() throws Exception {
        JsonNode patient = register(uniqueSurname());
        String id = patient.get("id").asString();

        JsonNode linked = objectMapper.readTree(
                mockMvc.perform(put("/patients/" + id + "/abha").with(as("RECEPTIONIST"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        // As people write it, with the grouping.
                                        "abhaNumber", "12-3456-7890-1234",
                                        "abhaAddress", "asha.menon@sbx"))))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertThat(linked.has("abhaNumber"))
                .as("a national identifier must not appear in an ordinary patient response, for "
                        + "the same reason the national id does not")
                .isFalse();
        assertThat(linked.has("abhaAddress")).isFalse();

        JsonNode identifiers = objectMapper.readTree(
                mockMvc.perform(get("/patients/" + id + "/identifiers").with(as("DOCTOR")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertThat(identifiers.get("abhaNumber").asString())
                .as("stored without its grouping, so two records of one person cannot differ by "
                        + "punctuation")
                .isEqualTo("12345678901234");
        assertThat(identifiers.get("abhaAddress").asString()).isEqualTo("asha.menon@sbx");
    }

    @Test
    @DisplayName("the ABHA column really is encrypted at rest")
    void abhaIsEncryptedAtRest() throws Exception {
        JsonNode patient = register(uniqueSurname());
        String id = patient.get("id").asString();
        mockMvc.perform(put("/patients/" + id + "/abha").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "abhaNumber", "98765432109876", "abhaAddress", "test@abdm"))))
                .andExpect(status().isOk());

        String stored = new JdbcTemplate(dataSource).queryForObject(
                "select abha_number from patient.patients where id = ?", String.class,
                java.util.UUID.fromString(id));
        assertThat(stored)
                .as("the ciphertext, not the digits — the same assertion the national id has")
                .isNotNull()
                .doesNotContain("98765432109876");
    }

    @Test
    @DisplayName("a malformed ABHA is refused, and linking is not a clinician's act")
    void abhaIsValidatedAndNarrowlyAuthorised() throws Exception {
        String id = register(uniqueSurname()).get("id").asString();

        mockMvc.perform(put("/patients/" + id + "/abha").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "abhaNumber", "1234", "abhaAddress", "short@sbx"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/patients/" + id + "/abha").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "abhaNumber", "12345678901234", "abhaAddress", "no-at-sign"))))
                .andExpect(status().isBadRequest());

        // A doctor reads a number a referral quotes and does not write one: linking happens with
        // the patient's card in front of you, which is the front desk.
        mockMvc.perform(put("/patients/" + id + "/abha").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "abhaNumber", "12345678901234", "abhaAddress", "asha@sbx"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the narrow lookup answers a name for an MRN and nothing else")
    void identifyAnswersFourFields() throws Exception {
        String surname = uniqueSurname();
        JsonNode patient = register(surname);

        JsonNode found = objectMapper.readTree(
                mockMvc.perform(get("/patients/identify?q=" + surname).with(as("CASHIER")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());

        assertThat(found.size()).isEqualTo(1);
        JsonNode row = found.get(0);
        assertThat(row.get("mrn").asString()).isEqualTo(patient.get("mrn").asString());
        assertThat(row.get("fullName").asString()).contains(surname);
        // Everything a billing desk does not need is a field a leak would carry, so the answer is
        // exactly four properties rather than a summary with the interesting ones removed.
        assertThat(row.propertyNames()).containsExactlyInAnyOrder("id", "mrn", "fullName",
                "active");
    }

    @Test
    @DisplayName("a cashier may put a name to an MRN and may not read the register")
    void identifyIsNarrowInBothDirections() throws Exception {
        mockMvc.perform(get("/patients").with(as("CASHIER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/patients/identify?q=nobody").with(as("CASHIER")))
                .andExpect(status().isOk());

        // And the other direction: everybody in CLINICAL_READ has the full search already, so this
        // endpoint is not theirs. A role list that grew to include them would make the narrowing
        // pointless without anything failing.
        mockMvc.perform(get("/patients/identify?q=nobody").with(as("DOCTOR")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/patients/identify?q=nobody").with(as("LAB_TECH")))
                .andExpect(status().isForbidden());
    }

    // ---- the birth cohort ----------------------------------------------------

    /**
     * A birthday nothing else in the database shares.
     *
     * <p>Every cohort test asks for exactly one date, so its fixtures have to be the only patients
     * born on it — the seeded demo patients and every other test's registrations live in the same
     * database. Far enough back that no seed row reaches it.
     */
    private static LocalDate aBirthdayOfItsOwn() {
        return LocalDate.of(1901, 1, 1)
                .plusDays(Math.abs(UUID.randomUUID().getMostSignificantBits() % 9000));
    }

    private JsonNode registerBornOn(String surname, LocalDate bornOn) throws Exception {
        Map<String, Object> body = new HashMap<>(validRegistration(surname));
        body.put("dateOfBirth", bornOn.toString());
        return objectMapper.readTree(
                mockMvc.perform(registrationRequest(body, "RECEPTIONIST"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder cohortRequest(LocalDate bornOn) {
        return get("/patients/cohort")
                .param("bornFrom", bornOn.toString())
                .param("bornTo", bornOn.toString());
    }

    private JsonNode cohort(LocalDate bornOn, String limit, String... roles) throws Exception {
        MockHttpServletRequestBuilder request = cohortRequest(bornOn);
        if (limit != null) {
            request = request.param("limit", limit);
        }
        return objectMapper.readTree(mockMvc.perform(request.with(as(roles)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("a cohort answers a birthday and a name, and nothing else")
    void cohortAnswersFourFields() throws Exception {
        LocalDate bornOn = aBirthdayOfItsOwn();
        JsonNode patient = registerBornOn(uniqueSurname(), bornOn);

        JsonNode answer = cohort(bornOn, null, "NURSE");

        assertThat(answer.get("members").size()).isEqualTo(1);
        JsonNode member = answer.get("members").get(0);
        assertThat(member.get("mrn").asString()).isEqualTo(patient.get("mrn").asString());
        assertThat(member.get("dateOfBirth").asString()).isEqualTo(bornOn.toString());
        // A birthday is what this endpoint exists to hand over, and everything else on a summary row
        // is a field a calling list does not need. Exactly four properties, rather than a summary
        // with the interesting ones removed.
        assertThat(member.propertyNames())
                .containsExactlyInAnyOrder("id", "mrn", "fullName", "dateOfBirth");
    }

    @Test
    @DisplayName("a cohort is its own permission: a nurse may list one and a cashier may not")
    void cohortHasItsOwnRole() throws Exception {
        LocalDate bornOn = aBirthdayOfItsOwn();

        mockMvc.perform(cohortRequest(bornOn).with(as("NURSE"))).andExpect(status().isOk());
        mockMvc.perform(cohortRequest(bornOn).with(as("DOCTOR"))).andExpect(status().isOk());

        // The cashier holds PATIENT_IDENTIFY, whose own javadoc names date of birth as the field
        // that endpoint exists to withhold. Granting this through that constant would have widened
        // by three words what a paragraph there spends narrowing, and this is the row that says so.
        mockMvc.perform(cohortRequest(bornOn).with(as("CASHIER"))).andExpect(status().isForbidden());
        // And the pharmacist, who holds IMMUNISATION_READ in the module that calls this: reading a
        // register is not running the calling list.
        mockMvc.perform(cohortRequest(bornOn).with(as("PHARMACIST")))
                .andExpect(status().isForbidden());
        mockMvc.perform(cohortRequest(bornOn).with(as("LAB_TECH")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a deceased child and an archived record are not on the calling list")
    void theDeceasedAndTheArchivedAreNotCalled() throws Exception {
        LocalDate bornOn = aBirthdayOfItsOwn();
        JsonNode living = registerBornOn(uniqueSurname(), bornOn);
        UUID died = UUID.fromString(registerBornOn(uniqueSurname(), bornOn).get("id").asString());
        UUID archived = UUID.fromString(registerBornOn(uniqueSurname(), bornOn).get("id").asString());

        patients.findById(died).ifPresent(patient -> {
            patient.setDeceased(true);
            patients.save(patient);
        });
        patients.findById(archived).ifPresent(patient -> {
            patient.setActive(false);
            patients.save(patient);
        });

        JsonNode answer = cohort(bornOn, null, "NURSE");

        // The predicate `identify` deliberately does not apply, and this is why: the worst thing a
        // calling list can produce is a telephone call to the mother of a dead child.
        assertThat(answer.get("members").size()).isEqualTo(1);
        assertThat(answer.get("members").get(0).get("mrn").asString())
                .isEqualTo(living.get("mrn").asString());
        assertThat(answer.get("total").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("a capped cohort says so rather than quietly returning a shorter list")
    void theCapAnnouncesItself() throws Exception {
        LocalDate bornOn = aBirthdayOfItsOwn();
        registerBornOn(uniqueSurname(), bornOn);
        registerBornOn(uniqueSurname(), bornOn);

        JsonNode answer = cohort(bornOn, "1", "NURSE");

        // The README's findings record what a silently capped pick-list cost once. A calling list
        // truncated in silence drops exactly the children nobody then telephones, so the cap is
        // stated on the response.
        assertThat(answer.get("members").size()).isEqualTo(1);
        assertThat(answer.get("total").asLong()).isEqualTo(2);
        assertThat(answer.get("truncated").asBoolean()).isTrue();
        assertThat(answer.get("note").asString()).contains("narrow the birth range");
    }

    @Test
    @DisplayName("a full cohort carries no truncation note at all")
    void anUntruncatedCohortSaysNothing() throws Exception {
        LocalDate bornOn = aBirthdayOfItsOwn();
        registerBornOn(uniqueSurname(), bornOn);

        JsonNode answer = cohort(bornOn, null, "NURSE");

        assertThat(answer.get("truncated").asBoolean()).isFalse();
        assertThat(answer.get("note").isNull()).isTrue();
    }

    @Test
    @DisplayName("a birth range that runs backwards is refused, not answered empty")
    void aBackwardsRangeIsRefused() throws Exception {
        mockMvc.perform(get("/patients/cohort")
                        .param("bornFrom", "2026-01-31")
                        .param("bornTo", "2026-01-01")
                        .with(as("NURSE")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("runs backwards")));
    }

    @Test
    @DisplayName("MRNs are unique across registrations")
    void mrnsAreUnique() throws Exception {
        List<String> issued = List.of(register(uniqueSurname()).get("mrn").asString(),
                register(uniqueSurname()).get("mrn").asString(),
                register(uniqueSurname()).get("mrn").asString());

        assertThat(issued).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("identifiers are stored as ciphertext, not plaintext")
    void identifiersAreEncryptedAtRest() throws Exception {
        UUID id = UUID.fromString(register(uniqueSurname()).get("id").asString());

        String storedNationalId = new JdbcTemplate(dataSource)
                .queryForObject("select national_id from patient.patients where id = ?", String.class, id);

        assertThat(storedNationalId).startsWith("v1:").doesNotContain("ABCDE1234F");
    }

    @Test
    @DisplayName("identifiers decrypt back to the submitted values")
    void identifiersDecryptForAuthorisedRoles() throws Exception {
        String id = register(uniqueSurname()).get("id").asString();

        mockMvc.perform(get("/patients/" + id + "/identifiers").with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nationalId").value("ABCDE1234F"))
                .andExpect(jsonPath("$.insurancePolicyNo").value("SH-99881"));
    }

    @Test
    @DisplayName("a matching surname and date of birth is reported as a duplicate with candidates")
    void duplicateRegistrationIsReported() throws Exception {
        String surname = uniqueSurname();
        register(surname);

        String conflict = mockMvc.perform(registrationRequest(validRegistration(surname), "RECEPTIONIST"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.candidates").isArray())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(conflict).get("candidates")).hasSize(1);
    }

    @Test
    @DisplayName("forceDuplicate registers anyway")
    void duplicateCanBeForced() throws Exception {
        String surname = uniqueSurname();
        register(surname);

        Map<String, Object> forced = new HashMap<>(validRegistration(surname));
        forced.put("forceDuplicate", true);

        mockMvc.perform(registrationRequest(forced, "RECEPTIONIST")).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a future date of birth is rejected with a field error")
    void futureDateOfBirthIsRejected() throws Exception {
        Map<String, Object> body = Map.of("firstName", "A", "lastName", uniqueSurname(),
                "dateOfBirth", LocalDate.now().plusYears(1).toString(), "sex", "MALE");

        mockMvc.perform(registrationRequest(body, "RECEPTIONIST"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dateOfBirth").exists());
    }

    @Test
    @DisplayName("a malformed body is a 400, not a 500")
    void malformedBodyIsBadRequest() throws Exception {
        mockMvc.perform(post("/patients").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("search finds a patient by surname and by MRN")
    void searchMatchesNameAndMrn() throws Exception {
        JsonNode created = register(uniqueSurname());

        mockMvc.perform(get("/patients").param("q", created.get("lastName").asString().toLowerCase())
                        .with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/patients").param("q", created.get("mrn").asString()).with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("the chart view loads allergies without a lazy-loading failure")
    void chartLoadsAllergies() throws Exception {
        String id = register(uniqueSurname()).get("id").asString();
        recordAllergy(id, "Penicillin", "LIFE_THREATENING");

        mockMvc.perform(get("/patients/" + id).with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCriticalAllergy").value(true))
                .andExpect(jsonPath("$.allergies", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    @DisplayName("search rows carry the critical-allergy marker")
    void searchRowsCarryCriticalAllergyMarker() throws Exception {
        JsonNode created = register(uniqueSurname());
        recordAllergy(created.get("id").asString(), "Sulfa", "SEVERE");

        mockMvc.perform(get("/patients").param("q", created.get("lastName").asString()).with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].hasCriticalAllergy").value(true));
    }

    @Test
    @DisplayName("the same allergy cannot be recorded twice, whatever the casing")
    void duplicateAllergyIsRejected() throws Exception {
        String id = register(uniqueSurname()).get("id").asString();
        recordAllergy(id, "Penicillin", "SEVERE");

        mockMvc.perform(post("/patients/" + id + "/allergies").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("substance", "penicillin", "severity", "MILD"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a lab technician may read a chart but not the encrypted identifiers")
    void labTechCannotReadIdentifiers() throws Exception {
        UUID id = patients.save(new Patient("MRN-TEST-" + UUID.randomUUID().toString().substring(0, 8),
                "Test", uniqueSurname(), LocalDate.of(1990, 1, 1), Sex.MALE)).getId();

        mockMvc.perform(get("/patients/" + id).with(as("LAB_TECH"))).andExpect(status().isOk());
        mockMvc.perform(get("/patients/" + id + "/identifiers").with(as("LAB_TECH")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the contact endpoint releases a phone and an email and no part of the record")
    void contactIsNarrow() throws Exception {
        JsonNode patient = register(uniqueSurname());
        String id = patient.get("id").asString();

        mockMvc.perform(get("/patients/" + id + "/contact").with(as("SERVICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.phone").exists())
                .andExpect(jsonPath("$.active").value(true))
                // The whole point of a separate endpoint: a service that needs somewhere to send a
                // message must not receive the chart. If any of these ever appear here, the
                // narrowing has been undone and CLINICAL_READ may as well have been granted.
                .andExpect(jsonPath("$.fullName").doesNotExist())
                .andExpect(jsonPath("$.mrn").doesNotExist())
                .andExpect(jsonPath("$.dateOfBirth").doesNotExist())
                .andExpect(jsonPath("$.allergies").doesNotExist())
                .andExpect(jsonPath("$.nationalId").doesNotExist());
    }

    @Test
    @DisplayName("a service account can read a contact and nothing else at all")
    void serviceAccountReachesOnlyTheContact() throws Exception {
        String id = register(uniqueSurname()).get("id").asString();

        mockMvc.perform(get("/patients/" + id + "/contact").with(as("SERVICE")))
                .andExpect(status().isOk());
        // SERVICE is deliberately not in CLINICAL_READ. A service that could read what the front
        // desk can read would make a separate role pointless, and the password for an unattended
        // account is the one most likely to end up in a deployment file somebody can read.
        mockMvc.perform(get("/patients/" + id).with(as("SERVICE"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/patients").with(as("SERVICE"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/patients/" + id + "/identifiers").with(as("SERVICE")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a clinician who can read the whole chart cannot read the contact endpoint")
    void contactIsNotClinicalRead() throws Exception {
        String id = register(uniqueSurname()).get("id").asString();

        // Not a gap. A doctor gets the phone number from the chart, where it belongs in context;
        // this endpoint exists only to avoid granting the chart, so widening it to everyone who
        // already has the chart would add a second, narrower door to the same room for no reason.
        mockMvc.perform(get("/patients/" + id + "/contact").with(as("DOCTOR")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/patients/" + id + "/contact").with(as("RECEPTIONIST")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/patients/" + id + "/contact").with(as("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a receptionist may register but not record clinical data")
    void receptionistCannotRecordAllergies() throws Exception {
        String id = register(uniqueSurname()).get("id").asString();

        mockMvc.perform(post("/patients/" + id + "/allergies").with(as("RECEPTIONIST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("substance", "Latex", "severity", "MILD"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("only an admin may archive a chart, and archiving is a soft delete")
    void archivingIsSoftAndAdminOnly() throws Exception {
        String id = register(uniqueSurname()).get("id").asString();

        mockMvc.perform(delete("/patients/" + id).with(as("DOCTOR"))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/patients/" + id).with(as("ADMIN"))).andExpect(status().isOk());

        // The row survives; it is merely inactive.
        assertThat(patients.findById(UUID.fromString(id))).isPresent()
                .get().extracting(Patient::isActive).isEqualTo(false);
        mockMvc.perform(get("/patients/" + id).with(as("DOCTOR"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("archived charts are hidden from search unless explicitly requested")
    void archivedChartsAreHiddenByDefault() throws Exception {
        JsonNode created = register(uniqueSurname());
        String surname = created.get("lastName").asString();
        mockMvc.perform(delete("/patients/" + created.get("id").asString()).with(as("ADMIN")));

        mockMvc.perform(get("/patients").param("q", surname).with(as("DOCTOR")))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/patients").param("q", surname).param("includeInactive", "true").with(as("DOCTOR")))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("an unknown patient id is a 404")
    void unknownPatientIsNotFound() throws Exception {
        mockMvc.perform(get("/patients/" + UUID.randomUUID()).with(as("DOCTOR"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the seeded departments are listed")
    void departmentsAreSeeded() throws Exception {
        mockMvc.perform(get("/departments").with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(6))));
    }

    @Test
    @DisplayName("staff are created with a department and found by search")
    void staffCanBeCreatedAndSearched() throws Exception {
        // Both the number and the searched name are unique per run: this suite runs against a
        // persistent database, so a shared name would match records left by earlier runs.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String employeeNo = "EMP-" + suffix;
        String fullName = "Dr Cardiologist " + suffix;

        mockMvc.perform(post("/staff").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "employeeNo", employeeNo, "fullName", fullName,
                                "designation", "Consultant", "departmentCode", "CARD"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.departmentName").value("Cardiology"));

        mockMvc.perform(get("/staff").param("q", fullName).with(as("NURSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].employeeNo").value(employeeNo));
    }

    @Test
    @DisplayName("a non-admin cannot create staff")
    void staffCreationIsAdminOnly() throws Exception {
        mockMvc.perform(post("/staff").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("employeeNo", "EMP-X",
                                "fullName", "X", "designation", "Y"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a department can be corrected and retired, and retiring it keeps its history")
    void aDepartmentCanBeRetired() throws Exception {
        String code = "DPT" + Long.toString(System.nanoTime(), 36).toUpperCase(java.util.Locale.ROOT)
                .substring(0, 6);
        mockMvc.perform(post("/departments").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code, "name", "Temporary Clinic"))))
                .andExpect(status().isCreated());

        // It could be created and never touched again, which for a vocabulary that staff rows,
        // appointments and encounters all reference is a gap rather than a simplification.
        mockMvc.perform(patch("/departments/" + code).with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Seasonal Clinic", "active", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Seasonal Clinic"))
                .andExpect(jsonPath("$.active").value(false));

        // Gone from the pick-list, still in the record: encounters recorded under it are real.
        assertThat(mockMvc.perform(get("/departments").with(as("NURSE")))
                .andReturn().getResponse().getContentAsString()).doesNotContain(code);
        assertThat(mockMvc.perform(get("/departments?includeInactive=true").with(as("NURSE")))
                .andReturn().getResponse().getContentAsString()).contains(code);
    }

    @Test
    @DisplayName("the department code itself cannot be rewritten")
    void aDepartmentCodeIsNotEditable() throws Exception {
        // Three services store the code and none of them would learn it had changed. Retiring is
        // what active is for, so the request shape simply has no code field to send.
        assertThat(PatientDtos.UpdateDepartmentRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("code");
    }

    @Test
    @DisplayName("only an administrator may change a department")
    void departmentUpdatesAreAdminOnly() throws Exception {
        mockMvc.perform(patch("/departments/CARD").with(as("DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a staff member with no department is still in the directory")
    void staffWithoutADepartmentAreSearchable() throws Exception {
        // Latent rather than live when it was found - every seeded staff row has a department -
        // but the staff form makes one optional, and a visiting consultant has none. The same
        // implicit-path inner join that hid two thirds of the rooms was here too.
        String employeeNo = "VISIT" + Long.toString(System.nanoTime(), 36)
                .toUpperCase(java.util.Locale.ROOT).substring(0, 6);
        mockMvc.perform(post("/staff").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "employeeNo", employeeNo,
                                "fullName", "Visiting Consultant " + employeeNo,
                                "designation", "Visiting Consultant"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.departmentCode").value(org.hamcrest.Matchers.nullValue()));

        assertThat(mockMvc.perform(get("/staff").param("q", employeeNo).with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .contains(employeeNo);

        assertThat(mockMvc.perform(get("/staff")
                        .param("q", employeeNo).param("department", "CARD")
                        .with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .doesNotContain(employeeNo);
    }

    @Test
    @DisplayName("a login resolves to the member of staff it belongs to, and only while they work here")
    void staffAreFoundByTheirLogin() throws Exception {
        UUID userId = UUID.randomUUID();
        String employeeNo = "LOGIN" + Long.toString(System.nanoTime(), 36)
                .toUpperCase(java.util.Locale.ROOT).substring(0, 6);
        String created = mockMvc.perform(post("/staff").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "employeeNo", employeeNo, "fullName", "Linked Person " + employeeNo,
                                "designation", "Consultant", "userId", userId.toString()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String staffId = objectMapper.readTree(created).get("id").asString();

        // The one mapping between a login and a person on this platform, and since the care-team
        // narrowing it is what turns a UUID in a request body into somebody who works here.
        mockMvc.perform(get("/staff/by-user/" + userId).with(as("DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeNo").value(employeeNo))
                .andExpect(jsonPath("$.userId").value(userId.toString()));

        mockMvc.perform(get("/staff/by-user/" + UUID.randomUUID()).with(as("DOCTOR")))
                .andExpect(status().isNotFound());

        // Somebody who has left is a miss, not a hit: a login that still resolves to a former
        // colleague is exactly the case the caller is trying to refuse.
        mockMvc.perform(patch("/staff/" + staffId).with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/staff/by-user/" + userId).with(as("DOCTOR")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("staff are found by employee number and specialty, not only by name")
    void staffSearchCoversWhatTheScreenPromises() throws Exception {
        // The search screen's placeholder has always said "name, employee number, specialty" and
        // the query matched the full name alone, so looking somebody up by the number on their
        // badge returned nothing.
        String employeeNo = "BADGE" + Long.toString(System.nanoTime(), 36)
                .toUpperCase(java.util.Locale.ROOT).substring(0, 6);
        String specialty = "Interventional Radiology " + employeeNo;
        // The name carries the badge too, and that is not decoration. The staff table is not
        // cleaned between runs, so every previous run of this test left a "Searchable Person"
        // behind; searching that name eventually returned a page of them that no longer included
        // this one, and the test failed as though the name search were broken. Every term here
        // has to name *this* record.
        String fullName = "Searchable Person " + employeeNo;
        mockMvc.perform(post("/staff").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "employeeNo", employeeNo, "fullName", fullName,
                                "designation", "Consultant", "specialty", specialty))))
                .andExpect(status().isCreated());

        for (String term : java.util.List.of(employeeNo, specialty, fullName)) {
            assertThat(mockMvc.perform(get("/staff").param("q", term).with(as("RECEPTIONIST")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString())
                    .as("searching for '%s' must find the record", term)
                    .contains(employeeNo);
        }
    }

    // ---- the wristband ---------------------------------------------------------

    @Test
    @DisplayName("the wristband is served as printable SVG that a browser will not cache")
    void wristbandIsUncacheableSvg() throws Exception {
        JsonNode created = register(uniqueSurname());

        String svg = mockMvc.perform(get("/patients/" + created.get("id").asText() + "/wristband")
                        .with(as("RECEPTIONIST")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("image/svg+xml")))
                // A band is generated for one patient at one moment. A cached one is how the wrong
                // wrist ends up with the right barcode.
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsString();

        assertThat(svg).startsWith("<svg");
        assertThat(svg).contains(created.get("mrn").asText());
    }

    @Test
    @DisplayName("the ward and the front desk may band a patient; a pathologist has nobody to band")
    void onlyThoseWithAPatientInFrontOfThemMayPrintABand() throws Exception {
        String id = register(uniqueSurname()).get("id").asText();

        // FRONT_DESK rather than CLINICAL_READ, and this is the row that says so: a pathologist
        // holds a clinical read and works down a bench rather than at a bedside.
        for (String role : List.of("RECEPTIONIST", "NURSE", "DOCTOR", "ADMIN")) {
            mockMvc.perform(get("/patients/" + id + "/wristband").with(as(role)))
                    .andExpect(status().isOk());
        }
        for (String role : List.of("PATHOLOGIST", "LAB_TECH", "PHARMACIST", "CASHIER")) {
            mockMvc.perform(get("/patients/" + id + "/wristband").with(as(role)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("an archived record cannot be banded, and the refusal says what to do instead")
    void archivedRecordCannotBeBanded() throws Exception {
        String id = register(uniqueSurname()).get("id").asText();
        mockMvc.perform(delete("/patients/" + id).with(as("ADMIN"))).andExpect(status().isOk());

        // A band goes onto a wrist to be scanned against live prescriptions. An archived MRN would
        // fail every one of those checks at the bedside, with nothing on the band to explain why.
        mockMvc.perform(get("/patients/" + id + "/wristband").with(as("RECEPTIONIST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("archived")));
    }

    @Test
    @DisplayName("a caller asking only for SVG still gets the reason, not an empty 500")
    void anErrorReachesACallerThatDoesNotAskForJson() throws Exception {
        // The defect this pins, found by hand against the live stack: an endpoint that produces
        // image/svg+xml has no acceptable representation for a problem+json error when the caller
        // asked strictly for SVG, so writing the error failed mid-write and the container answered
        // 500 with an empty body — in place of every 400, 404 and 409 on that endpoint. It had been
        // true of the specimen label since labels were built. Nothing caught it because a browser
        // sends a wildcard in its Accept and so did every test.
        //
        // GlobalExceptionHandler now states the error's content type, which makes Spring skip
        // negotiation. An error is not a negotiable representation.
        mockMvc.perform(get("/patients/00000000-0000-4000-8000-000000000000/wristband")
                        .accept(MediaType.valueOf("image/svg+xml"))
                        .with(as("RECEPTIONIST")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("not found")));
    }
}
