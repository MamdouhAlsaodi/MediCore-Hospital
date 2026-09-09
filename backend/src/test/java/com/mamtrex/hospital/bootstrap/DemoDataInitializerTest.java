package com.mamtrex.hospital.bootstrap;

import com.mamtrex.hospital.appointment.Appointment;
import com.mamtrex.hospital.appointment.AppointmentRepository;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.staff.StaffMember;
import com.mamtrex.hospital.staff.StaffMemberRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo seeding contract tests (docs/plan1.md Task 11).
 *
 * Pins the opt-in bootstrap behavior: the initializer does not exist by
 * default (no flag, no writes), the dedicated {@code medicore.demo.seed}
 * flag produces a coherent, obviously synthetic cohort, every seeded
 * appointment reference resolves to a seeded record, and repeated
 * initialization is idempotent without duplicate inflation or destructive
 * resets. Runs against an isolated in-memory H2 database (never the
 * production file store) with disposable synthetic test-only secrets; no
 * real personal or clinical data and no hardcoded credentials are involved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:demo-data-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "hospital.jwt.secret=" + DemoDataInitializerTest.TEST_JWT_SECRET,
        "HOSPITAL_ADMIN_PASSWORD=" + DemoDataInitializerTest.TEST_ACCOUNT_PASSWORD,
        "medicore.demo.seed=true"
})
class DemoDataInitializerTest {

    /** Long disposable test-only value; never a production secret. */
    static final String TEST_JWT_SECRET =
            "disposable-test-only-secret-demo-0123456789abcdef0123456789abcdef";

    /** Long disposable test-only value; never a production credential. */
    static final String TEST_ACCOUNT_PASSWORD = "disposable-test-password-demo-01";

    private static final Set<String> DEMO_MRNS = Set.of("DEMO-0001", "DEMO-0002", "DEMO-0003");
    private static final Set<String> DEMO_STAFF_CODES = Set.of("DEMO-STAFF-001", "DEMO-STAFF-002");

    @Autowired
    DemoDataInitializer initializer;

    @Autowired
    PatientRepository patients;

    @Autowired
    StaffMemberRepository staff;

    @Autowired
    AppointmentRepository appointments;

    /**
     * The flag creates exactly the named synthetic cohort at startup; this
     * test never calls the seeder itself, so a green run proves the runner
     * executed during context startup.
     */
    @Test
    void enabledFlagSeedsTheNamedSyntheticCohortAtStartup() {
        List<Patient> demoPatients = demoPatients();
        assertEquals(3, demoPatients.size(), "the demo cohort must contain exactly three synthetic patients");
        assertEquals(DEMO_MRNS, demoPatients.stream()
                .map(Patient::getMedicalRecordNumber)
                .collect(Collectors.toSet()), "demo patients must carry the stable DEMO medical record numbers");
        assertTrue(demoPatients.stream().allMatch(p -> p.getFullName().startsWith("Demo Patient ")),
                "demo patient names must be obviously synthetic");
        assertTrue(demoPatients.stream().allMatch(Patient::isActive), "demo patients must be active records");

        List<StaffMember> demoStaff = demoStaff();
        assertEquals(2, demoStaff.size(), "the demo cohort must contain exactly two synthetic professionals");
        assertEquals(DEMO_STAFF_CODES, demoStaff.stream()
                .map(StaffMember::getEmployeeCode)
                .collect(Collectors.toSet()), "demo professionals must carry the stable DEMO employee codes");
        assertTrue(demoStaff.stream().allMatch(s -> s.getFullName().startsWith("Demo ")),
                "professional names must be obviously synthetic");

        List<Appointment> demoAppointments = appointments.findAll().stream().toList();
        assertEquals(2, demoAppointments.size(), "the demo cohort must contain exactly two synthetic appointments");
    }

    /** Referential integrity: every appointment reference resolves to a seeded record. */
    @Test
    void everySeededAppointmentReferenceResolvesToASeededRecord() {
        List<Appointment> demoAppointments = appointments.findAll().stream().toList();
        assertFalse(demoAppointments.isEmpty(), "the enabled flag must have seeded appointments");
        for (Appointment appointment : demoAppointments) {
            UUID patientId = UUID.fromString(appointment.getPatientId());
            Patient patient = patients.findById(patientId)
                    .orElseThrow(() -> new AssertionError(
                            "appointment patientId must resolve to a persisted patient: " + patientId));
            assertTrue(patient.getMedicalRecordNumber().startsWith("DEMO-"),
                    "the resolved patient must belong to the synthetic demo cohort");
            assertEquals(patientId.toString(), appointment.getPatientId(),
                    "the stored reference must be the canonical UUID string of the persisted patient");

            UUID professionalId = UUID.fromString(appointment.getProfessionalId());
            StaffMember professional = staff.findById(professionalId)
                    .orElseThrow(() -> new AssertionError(
                            "appointment professionalId must resolve to a persisted professional: " + professionalId));
            assertTrue(professional.getEmployeeCode().startsWith("DEMO-STAFF-"),
                    "the resolved professional must belong to the synthetic demo cohort");
            assertEquals(professionalId.toString(), appointment.getProfessionalId(),
                    "the stored reference must be the canonical UUID string of the persisted professional");

            assertTrue(List.of("scheduled", "confirmed", "completed", "cancelled")
                            .contains(appointment.getStatus()),
                    "seeded appointment status must follow the lowercase contract");
            assertDoesNotThrow(() -> java.time.LocalDateTime.parse(appointment.getScheduledAt()),
                    "seeded scheduledAt must stay a parseable typed ISO value");
        }
    }

    /** Idempotency: a second run changes no counts and creates no duplicates. */
    @Test
    void repeatedInitializationIsIdempotentWithoutDuplicateInflation() {
        long patientsBefore = patients.count();
        long staffBefore = staff.count();
        long appointmentsBefore = appointments.count();
        assertEquals(3, demoPatients().size());
        assertEquals(2, demoStaff().size());
        assertEquals(2, appointmentsBefore);

        initializer.seedDemoCohort();
        initializer.seedDemoCohort();

        assertEquals(patientsBefore, patients.count(),
                "a repeated seed must not create additional patients");
        assertEquals(staffBefore, staff.count(),
                "a repeated seed must not create additional professionals");
        assertEquals(appointmentsBefore, appointments.count(),
                "a repeated seed must not create additional appointments");
        assertEquals(DEMO_MRNS, demoPatients().stream()
                .map(Patient::getMedicalRecordNumber)
                .collect(Collectors.toSet()), "the stable demo keys must stay unchanged after a repeated seed");
        assertEquals(DEMO_STAFF_CODES, demoStaff().stream()
                .map(StaffMember::getEmployeeCode)
                .collect(Collectors.toSet()), "the stable demo keys must stay unchanged after a repeated seed");
    }

    /**
     * Opt-in gate: without {@code medicore.demo.seed=true} the initializer
     * bean never exists and no store is touched — zero records are created.
     */
    @Test
    void initializerIsDisabledByDefaultAndNeverTouchesStores() {
        PatientRepository patientRepository = Mockito.mock(PatientRepository.class);
        StaffMemberRepository staffMemberRepository = Mockito.mock(StaffMemberRepository.class);
        AppointmentRepository appointmentRepository = Mockito.mock(AppointmentRepository.class);
        new ApplicationContextRunner()
                .withUserConfiguration(DemoDataInitializer.class)
                .withBean("patientRepository", PatientRepository.class, () -> patientRepository)
                .withBean("staffMemberRepository", StaffMemberRepository.class, () -> staffMemberRepository)
                .withBean("appointmentRepository", AppointmentRepository.class, () -> appointmentRepository)
                .run(context -> {
                    assertTrue(context.getBeansOfType(DemoDataInitializer.class).isEmpty(),
                            "the initializer bean must not exist when the demo flag is absent (default off)");
                    Mockito.verifyNoInteractions(patientRepository, staffMemberRepository, appointmentRepository);
                });
    }

    private List<Patient> demoPatients() {
        return patients.findAll().stream()
                .filter(p -> p.getMedicalRecordNumber().startsWith("DEMO-"))
                .collect(Collectors.toList());
    }

    private List<StaffMember> demoStaff() {
        return staff.findAll().stream()
                .filter(s -> s.getEmployeeCode() != null && s.getEmployeeCode().startsWith("DEMO-STAFF-"))
                .collect(Collectors.toList());
    }
}
