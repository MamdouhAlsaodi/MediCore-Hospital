package com.mamtrex.hospital.reporting;

import com.mamtrex.hospital.admission.AdmissionRepository;
import com.mamtrex.hospital.appointment.AppointmentRepository;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.billing.InvoiceRepository;
import com.mamtrex.hospital.bed.Bed;
import com.mamtrex.hospital.bed.BedRepository;
import com.mamtrex.hospital.emergency.EmergencyVisitRepository;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalOrganization;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import com.mamtrex.hospital.patient.PatientRepository;
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
 * Aggregation owner for the branch and network command centers
 * (docs/plan3.md Task 10, §4.7). Every summary is derived server-side from
 * grouped repository counts restricted to an explicit branch-id set — the
 * acting branch for the branch view, and exactly the organization's active
 * branches for the network view — so no path ever performs a whole-table
 * read, and the query count is bounded by the number of tables, not by the
 * number of cards or branches.
 *
 * <p>Scope is never client input: the branch view reads the branch bound to
 * the verified {@link ActingContext} (rebuilt by the JWT filter from server
 * state on every request), and the network view additionally requires that
 * context to be an enabled ADMIN assignment with ORGANIZATION scope —
 * enabled because a context only exists when the filter rebuilt it from an
 * enabled assignment, and refused with the ordinary denial otherwise. The
 * organization totals are exactly the sum over the returned per-branch
 * summaries; legacy null-ownership rows are invisible, mirroring every
 * other branch-scoped read of this phase. Order is deterministic: branches
 * appear in the repository's code order, and the DTO record declaration
 * fixes the JSON key order. Counts/latency are observed in Task 13, not
 * advertised here. Reads record no audit events.</p>
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

    public DashboardService(PatientRepository patients, AppointmentRepository appointments,
                            AdmissionRepository admissions, EmergencyVisitRepository emergencyVisits,
                            InvoiceRepository invoices, BedRepository beds,
                            BranchRepository branches, HospitalOrganizationRepository organizations,
                            Clock clock) {
        this.patients = patients;
        this.appointments = appointments;
        this.admissions = admissions;
        this.emergencyVisits = emergencyVisits;
        this.invoices = invoices;
        this.beds = beds;
        this.branches = branches;
        this.organizations = organizations;
        this.clock = clock;
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
        return toSummary(branch, accumulate(List.of(branch.getId())));
    }

    /**
     * Typed organization comparison for an enabled organization-scoped ADMIN
     * context: every active branch of the organization in deterministic code
     * order (all-zero summaries included) plus totals equal to the sum over
     * those branches. Any other context receives the ordinary denial.
     */
    @Transactional(readOnly = true)
    public DashboardDtos.NetworkSummary networkSummary() {
        ActingContext context = currentContext();
        requireOrganizationAdmin(context);
        HospitalOrganization organization = organizations.findById(context.organizationId())
                .orElseThrow(() -> new AccessDeniedException("The acting organization is not available"));
        List<Branch> activeBranches =
                branches.findByOrganizationIdAndActiveTrueOrderByCodeAsc(organization.getId());
        // One fold over the whole authorized branch set — the query count is
        // bounded by the number of tables, never by the number of branches.
        Acc acc = accumulate(branchIds(activeBranches));
        List<DashboardDtos.BranchSummary> summaries = activeBranches.stream()
                .map(branch -> toSummary(branch, acc))
                .toList();
        return new DashboardDtos.NetworkSummary(organization.getId(), organization.getName(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::patients).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::appointments).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::admissions).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::emergencyVisits).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::invoices).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::openAdmissions).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::activeEmergencyVisits).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::bedsAvailable).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::bedsOccupied).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::bedsMaintenance).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::bedsOutOfService).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::todayAppointments).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::invoicesDraft).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::invoicesIssued).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::invoicesPaid).sum(),
                summaries.stream().mapToLong(DashboardDtos.BranchSummary::invoicesVoid).sum(),
                summaries);
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
        Acc.Branch value = accumulate(List.of(defaultBranch.getId()))
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

    /** Only an enabled ADMIN context with ORGANIZATION scope may compare branches. */
    private static void requireOrganizationAdmin(ActingContext context) {
        if (context.role() != Role.ADMIN || context.scope() != AssignmentScope.ORGANIZATION) {
            throw new AccessDeniedException(
                    "The network view is limited to organization-scoped administrators");
        }
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
     * Folds the grouped per-table counts of one explicit branch-id set into
     * one accumulator per branch — one query per table plus the today
     * window, never per card.
     */
    private Acc accumulate(Collection<UUID> branchIds) {
        Acc acc = new Acc();
        for (PatientRepository.BranchMetric metric : patients.countByBranchIdInGrouped(branchIds)) {
            acc.of(metric.getBranchId()).patients += metric.getTotal();
        }
        for (AppointmentRepository.BranchMetric metric : appointments.countByBranchIdInGrouped(branchIds)) {
            acc.of(metric.getBranchId()).appointments += metric.getTotal();
        }
        Window today = todayWindow();
        for (AppointmentRepository.BranchMetric metric : appointments
                .countByBranchIdAndScheduledAtRangeGrouped(branchIds, today.start(), today.end())) {
            acc.of(metric.getBranchId()).todayAppointments += metric.getTotal();
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

    /**
     * Today's half-open [start, end) window in the same canonical
     * LocalDateTime.toString() representation the appointment service
     * writes, derived from the server's explicit clock — never client
     * input. Legacy rows without a canonical value stay honestly outside
     * the window instead of being reinterpreted.
     */
    private Window todayWindow() {
        LocalDate today = LocalDate.now(clock);
        return new Window(today.atStartOfDay().toString(), today.plusDays(1).atStartOfDay().toString());
    }

    private record Window(String start, String end) {
    }

    private static DashboardDtos.BranchSummary toSummary(Branch branch, Acc acc) {
        Acc.Branch value = acc.byBranch.getOrDefault(branch.getId(), new Acc.Branch());
        return new DashboardDtos.BranchSummary(branch.getId(), branch.getCode(), branch.getName(),
                value.patients, value.appointments, value.admissions, value.emergencyVisits, value.invoices,
                value.openAdmissions, value.activeEmergencyVisits,
                value.bedsAvailable, value.bedsOccupied, value.bedsMaintenance, value.bedsOutOfService,
                value.todayAppointments,
                value.invoicesDraft, value.invoicesIssued, value.invoicesPaid, value.invoicesVoid);
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
        }
    }
}
