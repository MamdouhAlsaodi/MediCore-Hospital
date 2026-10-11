package com.mamtrex.hospital.reporting;

import com.mamtrex.hospital.admission.AdmissionRepository;
import com.mamtrex.hospital.appointment.AppointmentRepository;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.billing.InvoiceRepository;
import com.mamtrex.hospital.bed.Bed;
import com.mamtrex.hospital.bed.BedRepository;
import com.mamtrex.hospital.emergency.EmergencyVisitRepository;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import com.mamtrex.hospital.patient.PatientRepository;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Branch-level grouped metric reader shared by branch, hospital, and network
 * command centers. The dedicated {@link NetworkOperationsDashboardService}
 * owns hospital/network authority and hierarchy; this service folds one
 * explicit server-selected branch set without per-branch SQL statements.
 * Branch-local today windows are grouped into one query by distinct window.
 * All reads are synthetic operational counts, not clinical interpretation.
 */
@Service
public class DashboardService {

    /** The only open-admission state (docs/plan2.md Task 2 lifecycle). */
    private static final String OPEN_ADMISSION_STATUS = "ADMITTED";

    /** The two emergency states that count as active (docs/plan2.md Task 3 lifecycle). */
    private static final Set<String> ACTIVE_EMERGENCY_STATUSES = Set.of("WAITING", "IN_TREATMENT");

    /** The simulated invoice buckets (docs/plan2.md Task 4; financial simulation only). */
    private static final String INVOICE_DRAFT = "DRAFT";
    private static final String INVOICE_ISSUED = "ISSUED";
    private static final String INVOICE_PAID = "PAID";
    private static final String INVOICE_VOID = "VOID";
    private static final Set<String> INVOICE_BUCKETS = Set.of(INVOICE_DRAFT, INVOICE_ISSUED, INVOICE_PAID, INVOICE_VOID);

    private final PatientRepository patients;
    private final AppointmentRepository appointments;
    private final AdmissionRepository admissions;
    private final EmergencyVisitRepository emergencyVisits;
    private final InvoiceRepository invoices;
    private final BedRepository beds;
    private final BranchRepository branches;
    private final HospitalOrganizationRepository organizations;
    private final Clock clock;
    private final EntityManager entityManager;
    private final com.mamtrex.hospital.transfer.TransferBedReservationRepository reservations;
    private final com.mamtrex.hospital.transfer.TransferRepository transfers;
    private final org.springframework.beans.factory.ObjectProvider<NetworkOperationsDashboardService> networkOperations;

    public DashboardService(PatientRepository patients, AppointmentRepository appointments,
                            AdmissionRepository admissions, EmergencyVisitRepository emergencyVisits,
                            InvoiceRepository invoices, BedRepository beds,
                            BranchRepository branches, HospitalOrganizationRepository organizations,
                            Clock clock, EntityManager entityManager,
                            com.mamtrex.hospital.transfer.TransferBedReservationRepository reservations,
                            com.mamtrex.hospital.transfer.TransferRepository transfers,
                            org.springframework.beans.factory.ObjectProvider<NetworkOperationsDashboardService> networkOperations) {
        this.patients = patients;
        this.appointments = appointments;
        this.admissions = admissions;
        this.emergencyVisits = emergencyVisits;
        this.invoices = invoices;
        this.beds = beds;
        this.branches = branches;
        this.organizations = organizations;
        this.clock = clock;
        this.entityManager = entityManager;
        this.reservations = reservations;
        this.transfers = transfers;
        this.networkOperations = networkOperations;
    }

    /**
     * The server's explicit clock (docs/plan3.md §4.7): the single time
     * source for today's appointment window. Declared as a bean here so the
     * command center owns one narrow seam; tests override the bean to pin
     * the day boundary deterministically. No time-zone policy is invented —
     * the JVM default zone stays, and branch-local zones remain a later
     * owner decision (docs/plan3.md §4.7).
     */
    @Configuration(proxyBeanMethods = false)
    static class DashboardTimeConfig {
        @Bean
        Clock dashboardClock() {
            return Clock.systemDefaultZone();
        }
    }

    /** Typed summary of the acting branch, in the DTO's deterministic declaration order. */
    @Transactional(readOnly = true)
    public DashboardDtos.BranchSummary branchSummary() {
        Branch branch = branches.findById(currentContext().branchId())
                .orElseThrow(() -> new AccessDeniedException("The acting branch is not available"));
        return toSummary(branch, accumulate(List.of(branch)));
    }

    /** Retained service API for existing callers; aggregation lives in the dedicated owner. */
    @Deprecated
    public DashboardDtos.NetworkSummary networkSummary() {
        return networkOperations.getObject().networkSummary();
    }

    /** One batched fold shared with the hospital/network aggregation owner. */
    List<DashboardDtos.BranchSummary> summaries(List<Branch> selected) {
        if (selected.isEmpty()) return List.of();
        Acc acc = accumulate(selected);
        return selected.stream().map(branch -> toSummary(branch, acc)).toList();
    }

    private static List<UUID> branchIds(List<Branch> activeBranches) {
        return activeBranches.stream().map(Branch::getId).toList();
    }

    /**
     * Retired flat view (the docs/plan2.md Task 5 key shape) of one branch's
     * summary, retained during Phase 3 for the bootstrap fixture evidence
     * that predates this task. No HTTP path exposes it since Task 10 — the
     * command centers serve the typed DTOs — and the aggregation here stays
     * fully branch-scoped: the single organization's deterministic first
     * active branch in code order, counted with exactly the same grouped
     * queries as every other summary, never a whole-table count. Task 12
     * rebuilds the fixture evidence on the typed contracts, at which point
     * this adapter is removed.
     */
    @Deprecated
    @Transactional(readOnly = true)
    public Map<String, Long> summary() {
        HospitalOrganization organization = organizations.findAll().stream().findFirst()
                .orElseThrow(() -> new AccessDeniedException("No organization is available"));
        Branch defaultBranch = branches.findByOrganizationIdAndActiveTrueOrderByCodeAsc(organization.getId())
                .stream().findFirst()
                .orElseThrow(() -> new AccessDeniedException("No active branch is available"));
        Acc.Branch value = accumulate(List.of(defaultBranch))
                .byBranch.getOrDefault(defaultBranch.getId(), new Acc.Branch());
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("patients", value.patients);
        counts.put("appointments", value.appointments);
        counts.put("admissions", value.admissions);
        counts.put("emergencyVisits", value.emergencyVisits);
        counts.put("invoices", value.invoices);
        counts.put("openAdmissions", value.openAdmissions);
        counts.put("activeEmergencyVisits", value.activeEmergencyVisits);
        counts.put("invoicesDraft", value.invoicesDraft);
        counts.put("invoicesIssued", value.invoicesIssued);
        counts.put("invoicesPaid", value.invoicesPaid);
        counts.put("invoicesVoid", value.invoicesVoid);
        return counts;
    }

    /* Fail-closed seam: the JWT filter guarantees an ActingContext principal
     * and an existing active selected branch for every authorized request;
     * anything else is refused, never guessed. */
    private static ActingContext currentContext() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ActingContext context) {
            return context;
        }
        throw new AccessDeniedException("No acting context is available");
    }

    /**
     * Folds the grouped per-table counts of one explicit authorized branch
     * set into one accumulator per branch — one query per table plus the
     * per-branch today window, never per card. Phase 4 (FR-012): each
     * branch's "today" derives from its own IANA zone as exact half-open
     * instants; the server clock supplies the instant, never the zone.
     */
    private Acc accumulate(List<Branch> branchList) {
        Collection<UUID> branchIds = branchIds(branchList);
        Acc acc = new Acc();
        for (PatientRepository.BranchMetric metric : patients.countByBranchIdInGrouped(branchIds)) {
            acc.of(metric.getBranchId()).patients += metric.getTotal();
        }
        for (AppointmentRepository.BranchMetric metric : appointments.countByBranchIdInGrouped(branchIds)) {
            acc.of(metric.getBranchId()).appointments += metric.getTotal();
        }
        countTodayAppointments(branchList, acc);
        UUID organizationId = branchList.getFirst().getHospital().getOrganization().getId();
        for (var row : reservations.countActiveCapacityByBranch(organizationId, branchIds)) {
            acc.of(row.getBranchId()).bedsReservedForTransfer += row.getTotal();
        }
        for (var row : appointments.countScheduledCapacityByBranch(organizationId, branchIds)) {
            acc.of(row.getBranchId()).appointmentsScheduled += row.getTotal();
        }
        for (var row : transfers.countActiveByDestinationBranch(organizationId, branchIds)) {
            acc.of(row.getBranchId()).activeTransfers += row.getTotal();
        }
        for (AdmissionRepository.BranchStatusCount row : admissions.countByBranchIdInGroupedByStatus(branchIds)) {
            Acc.Branch value = acc.of(row.getBranchId());
            value.admissions += row.getTotal();
            if (OPEN_ADMISSION_STATUS.equals(row.getStatus())) {
                value.openAdmissions += row.getTotal();
            }
        }
        for (EmergencyVisitRepository.BranchStatusCount row : emergencyVisits.countByBranchIdInGroupedByStatus(branchIds)) {
            Acc.Branch value = acc.of(row.getBranchId());
            value.emergencyVisits += row.getTotal();
            if (ACTIVE_EMERGENCY_STATUSES.contains(row.getStatus())) {
                value.activeEmergencyVisits += row.getTotal();
            }
        }
        for (InvoiceRepository.BranchStatusCount row : invoices.countByBranchIdInGroupedByStatus(branchIds)) {
            Acc.Branch value = acc.of(row.getBranchId());
            value.invoices += row.getTotal();
            if (INVOICE_BUCKETS.contains(row.getStatus())) {
                switch (row.getStatus()) {
                    case INVOICE_DRAFT -> value.invoicesDraft += row.getTotal();
                    case INVOICE_ISSUED -> value.invoicesIssued += row.getTotal();
                    case INVOICE_PAID -> value.invoicesPaid += row.getTotal();
                    case INVOICE_VOID -> value.invoicesVoid += row.getTotal();
                    default -> throw new IllegalStateException("Unbucketed invoice status");
                }
            }
        }
        for (BedRepository.BranchStatusCount row : beds.countByBranchIdInGroupedByStatus(branchIds)) {
            Acc.Branch value = acc.of(row.getBranchId());
            switch (row.getStatus()) {
                case Bed.STATUS_AVAILABLE -> value.bedsAvailable += row.getTotal();
                case Bed.STATUS_OCCUPIED -> value.bedsOccupied += row.getTotal();
                case Bed.STATUS_MAINTENANCE -> value.bedsMaintenance += row.getTotal();
                case Bed.STATUS_OUT_OF_SERVICE -> value.bedsOutOfService += row.getTotal();
                default -> throw new IllegalStateException("Unbucketed bed status");
            }
        }
        return acc;
    }

    /** Group branch-local day windows into one bounded grouped SQL statement. */
    private void countTodayAppointments(List<Branch> selected, Acc acc) {
        Map<Window, List<UUID>> windows = new LinkedHashMap<>();
        for (Branch branch : selected) {
            windows.computeIfAbsent(todayWindow(branch), ignored -> new java.util.ArrayList<>()).add(branch.getId());
        }
        StringBuilder predicate = new StringBuilder();
        int index = 0;
        for (Window ignored : windows.keySet()) {
            if (index > 0) predicate.append(" or ");
            predicate.append("(a.branch.id in :ids").append(index)
                    .append(" and a.scheduledAt >= :start").append(index)
                    .append(" and a.scheduledAt < :end").append(index).append(')');
            index++;
        }
        var query = entityManager.createQuery("select a.branch.id, count(a) from Appointment a where "
                + predicate + " group by a.branch.id", Object[].class);
        index = 0;
        for (var entry : windows.entrySet()) {
            query.setParameter("ids" + index, entry.getValue());
            query.setParameter("start" + index, entry.getKey().start());
            query.setParameter("end" + index, entry.getKey().end());
            index++;
        }
        for (Object[] row : query.getResultList()) {
            acc.of((UUID) row[0]).todayAppointments = (Long) row[1];
        }
    }

    /**
     * Today's half-open [start, end) window for one branch, in that
     * branch's own IANA zone (FR-012), as exact instants derived from the
     * server's explicit clock — never client input, never the JVM default
     * zone. A legacy branch without a configured zone fails closed.
     */
    private Window todayWindow(Branch branch) {
        java.time.ZoneId zone = branch.getTimeZone();
        if (zone == null) {
            throw new IllegalStateException(
                    "Branch " + branch.getCode() + " has no configured time zone; unknown legacy rows are never guessed");
        }
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return new Window(today.atStartOfDay(zone).toInstant(), today.plusDays(1).atStartOfDay(zone).toInstant());
    }

    private record Window(java.time.Instant start, java.time.Instant end) {
    }

    private static DashboardDtos.BranchSummary toSummary(Branch branch, Acc acc) {
        Acc.Branch value = acc.byBranch.getOrDefault(branch.getId(), new Acc.Branch());
        return new DashboardDtos.BranchSummary(branch.getId(), branch.getCode(), branch.getName(),
                value.patients, value.appointments, value.admissions, value.emergencyVisits, value.invoices,
                value.openAdmissions, value.activeEmergencyVisits,
                value.bedsAvailable, value.bedsOccupied, value.bedsMaintenance, value.bedsOutOfService,
                value.todayAppointments,
                value.invoicesDraft, value.invoicesIssued, value.invoicesPaid, value.invoicesVoid,
                value.bedsReservedForTransfer, value.appointmentsScheduled, value.activeTransfers);
    }

    /**
     * Mutable fold target for the grouped counts; branch ids outside the
     * queried set never appear because every query filters on that set, and
     * a branch without rows folds to the honest all-zero accumulator.
     */
    private static final class Acc {
        private final Map<UUID, Branch> byBranch = new HashMap<>();

        private Branch of(UUID branchId) {
            return byBranch.computeIfAbsent(branchId, id -> new Branch());
        }

        private static final class Branch {
            private long patients;
            private long appointments;
            private long admissions;
            private long emergencyVisits;
            private long invoices;
            private long openAdmissions;
            private long activeEmergencyVisits;
            private long bedsAvailable;
            private long bedsOccupied;
            private long bedsMaintenance;
            private long bedsOutOfService;
            private long todayAppointments;
            private long invoicesDraft;
            private long invoicesIssued;
            private long invoicesPaid;
            private long invoicesVoid;
            private long bedsReservedForTransfer;
            private long appointmentsScheduled;
            private long activeTransfers;
        }
    }
}
