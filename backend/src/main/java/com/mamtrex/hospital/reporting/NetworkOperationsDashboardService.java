package com.mamtrex.hospital.reporting;

import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalFacility;
import com.mamtrex.hospital.organization.HospitalFacilityRepository;
import com.mamtrex.hospital.organization.HospitalOrganizationRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Scope and hierarchical folds for synthetic, operational-only command-center reads. */
@Service
public class NetworkOperationsDashboardService {
    private final DashboardService dashboard;
    private final BranchRepository branches;
    private final HospitalFacilityRepository hospitals;
    private final HospitalOrganizationRepository organizations;

    public NetworkOperationsDashboardService(DashboardService dashboard, BranchRepository branches,
            HospitalFacilityRepository hospitals, HospitalOrganizationRepository organizations) {
        this.dashboard = dashboard;
        this.branches = branches;
        this.hospitals = hospitals;
        this.organizations = organizations;
    }

    // The grouped metrics are separate statements: keep a single committed snapshot across all folds.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardDtos.HospitalSummary hospitalSummary() {
        ActingContext context = current();
        if (context.scope() != AssignmentScope.HOSPITAL && context.scope() != AssignmentScope.ORGANIZATION) throw denied();
        HospitalFacility hospital = hospitals.findByIdAndOrganizationId(context.hospitalId(), context.organizationId())
                .filter(HospitalFacility::isActive).orElseThrow(NetworkOperationsDashboardService::denied);
        List<Branch> selected = branches.findByHospitalIdAndActiveTrueOrderByCodeAsc(hospital.getId());
        return hospital(hospital, dashboard.summaries(selected));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardDtos.NetworkSummary networkSummary() {
        ActingContext context = current();
        if (context.scope() != AssignmentScope.ORGANIZATION || context.role() != Role.ADMIN) throw denied();
        var organization = organizations.findById(context.organizationId()).orElseThrow(NetworkOperationsDashboardService::denied);
        List<HospitalFacility> active = hospitals.findByOrganizationIdAndActiveTrueOrderByCodeAsc(organization.getId());
        Set<UUID> ids = active.stream().map(HospitalFacility::getId).collect(Collectors.toSet());
        List<Branch> selected = branches.findByOrganizationIdAndActiveTrueOrderByCodeAsc(organization.getId()).stream()
                .filter(branch -> ids.contains(branch.getHospital().getId())).toList();
        List<DashboardDtos.BranchSummary> all = dashboard.summaries(selected);
        Map<UUID, DashboardDtos.BranchSummary> byBranch = all.stream()
                .collect(Collectors.toMap(DashboardDtos.BranchSummary::branchId, row -> row));
        Map<UUID, List<DashboardDtos.BranchSummary>> grouped = selected.stream().collect(Collectors.groupingBy(
                branch -> branch.getHospital().getId(), Collectors.mapping(
                        branch -> byBranch.get(branch.getId()), Collectors.toList())));
        List<DashboardDtos.HospitalSummary> summaries = active.stream()
                .map(h -> hospital(h, grouped.getOrDefault(h.getId(), List.of()))).toList();
        return new DashboardDtos.NetworkSummary(organization.getId(), organization.getName(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::patients).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::appointments).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::admissions).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::emergencyVisits).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::invoices).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::openAdmissions).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::activeEmergencyVisits).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::bedsAvailable).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::bedsOccupied).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::bedsMaintenance).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::bedsOutOfService).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::todayAppointments).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::invoicesDraft).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::invoicesIssued).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::invoicesPaid).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::invoicesVoid).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::bedsReservedForTransfer).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::appointmentsScheduled).sum(),
                all.stream().mapToLong(DashboardDtos.BranchSummary::activeTransfers).sum(), all, summaries);
    }

    private static DashboardDtos.HospitalSummary hospital(HospitalFacility h, List<DashboardDtos.BranchSummary> rows) {
        return new DashboardDtos.HospitalSummary(h.getId(), h.getCode(), h.getName(), h.getRegionLabel(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::patients).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::appointments).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::admissions).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::emergencyVisits).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::invoices).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::openAdmissions).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::activeEmergencyVisits).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::bedsAvailable).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::bedsOccupied).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::bedsMaintenance).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::bedsOutOfService).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::todayAppointments).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::invoicesDraft).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::invoicesIssued).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::invoicesPaid).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::invoicesVoid).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::bedsReservedForTransfer).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::appointmentsScheduled).sum(),
                rows.stream().mapToLong(DashboardDtos.BranchSummary::activeTransfers).sum(), rows);
    }

    private static ActingContext current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ActingContext context) return context;
        throw denied();
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Dashboard scope is not available"); }
}
