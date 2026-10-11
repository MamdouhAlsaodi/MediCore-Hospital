package com.mamtrex.hospital.reporting;

import com.mamtrex.hospital.appointment.AppointmentRepository;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.auth.AssignmentScope;
import com.mamtrex.hospital.auth.Role;
import com.mamtrex.hospital.bed.Bed;
import com.mamtrex.hospital.bed.BedRepository;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalFacility;
import com.mamtrex.hospital.organization.HospitalFacilityRepository;
import com.mamtrex.hospital.transfer.TransferBedReservationRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read-only capacity derived only from the reloaded acting assignment and active server-owned hierarchy. */
@Service
public class FacilityCapacityService {
    public record CapacitySummary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID branchId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long bedsAvailable,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long bedsReservedForTransfer,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long bedsOccupied,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long appointmentsScheduled) {}
    public record HospitalCapacitySummary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID hospitalId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long bedsAvailable,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long bedsReservedForTransfer,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long bedsOccupied,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long appointmentsScheduled,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CapacitySummary> branches) {}

    private final BranchRepository branches;
    private final HospitalFacilityRepository hospitals;
    private final BedRepository beds;
    private final TransferBedReservationRepository reservations;
    private final AppointmentRepository appointments;

    public FacilityCapacityService(BranchRepository branches, HospitalFacilityRepository hospitals,
            BedRepository beds, TransferBedReservationRepository reservations, AppointmentRepository appointments) {
        this.branches = branches;
        this.hospitals = hospitals;
        this.beds = beds;
        this.reservations = reservations;
        this.appointments = appointments;
    }

    // Bed and reservation counts are separate grouped statements. PostgreSQL READ COMMITTED
    // could combine a pre-completion available bed with a post-completion consumed reservation.
    // One repeatable-read snapshot keeps all capacity metrics on the same committed state.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CapacitySummary branch() {
        ActingContext context = current();
        Branch branch = branches.findByIdAndOrganizationId(context.branchId(), context.organizationId())
                .filter(b -> b.isActive() && b.getHospital().getId().equals(context.hospitalId()))
                .orElseThrow(FacilityCapacityService::denied);
        requireHospital(context.organizationId(), context.hospitalId());
        return summaries(context.organizationId(), List.of(branch)).get(branch.getId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HospitalCapacitySummary hospital() {
        ActingContext context = current();
        if (context.scope() != AssignmentScope.HOSPITAL && context.scope() != AssignmentScope.ORGANIZATION)
            throw denied();
        requireHospital(context.organizationId(), context.hospitalId());
        List<Branch> selected = branches.findByHospitalIdAndActiveTrueOrderByCodeAsc(context.hospitalId());
        return aggregate(context.hospitalId(), selected, summaries(context.organizationId(), selected));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<HospitalCapacitySummary> network() {
        ActingContext context = current();
        if (context.scope() != AssignmentScope.ORGANIZATION || context.role() != Role.ADMIN) throw denied();
        List<HospitalFacility> active = hospitals.findByOrganizationIdAndActiveTrueOrderByCodeAsc(context.organizationId());
        Set<UUID> hospitalIds = active.stream().map(HospitalFacility::getId).collect(Collectors.toSet());
        List<Branch> authorized = branches.findByOrganizationIdAndActiveTrueOrderByCodeAsc(context.organizationId())
                .stream().filter(b -> hospitalIds.contains(b.getHospital().getId())).toList();
        Map<UUID, CapacitySummary> counts = summaries(context.organizationId(), authorized);
        return active.stream().map(h -> aggregate(h.getId(), authorized.stream()
                .filter(b -> b.getHospital().getId().equals(h.getId())).toList(), counts)).toList();
    }

    private HospitalCapacitySummary aggregate(UUID hospitalId, List<Branch> branchSet,
                                               Map<UUID, CapacitySummary> counts) {
        List<CapacitySummary> rows = branchSet.stream().map(b -> counts.get(b.getId())).toList();
        return new HospitalCapacitySummary(hospitalId,
                rows.stream().mapToLong(CapacitySummary::bedsAvailable).sum(),
                rows.stream().mapToLong(CapacitySummary::bedsReservedForTransfer).sum(),
                rows.stream().mapToLong(CapacitySummary::bedsOccupied).sum(),
                rows.stream().mapToLong(CapacitySummary::appointmentsScheduled).sum(), rows);
    }

    private Map<UUID, CapacitySummary> summaries(UUID organizationId, List<Branch> selected) {
        if (selected.isEmpty()) return Map.of(); // never issue an unbounded IN () query
        List<UUID> ids = selected.stream().map(Branch::getId).toList();
        Map<UUID, long[]> counts = new HashMap<>();
        for (UUID id : ids) counts.put(id, new long[4]);
        for (BedRepository.BranchStatusCount row : beds.countCapacityByBranch(organizationId, ids)) {
            long[] value = counts.get(row.getBranchId());
            if (value == null) continue;
            if (Bed.STATUS_AVAILABLE.equals(row.getStatus())) value[0] = row.getTotal();
            if (Bed.STATUS_OCCUPIED.equals(row.getStatus())) value[2] = row.getTotal();
        }
        for (TransferBedReservationRepository.BranchMetric row : reservations.countActiveCapacityByBranch(organizationId, ids)) {
            long[] value = counts.get(row.getBranchId());
            if (value != null) value[1] = row.getTotal();
        }
        for (AppointmentRepository.BranchMetric row : appointments.countScheduledCapacityByBranch(organizationId, ids)) {
            long[] value = counts.get(row.getBranchId());
            if (value != null) value[3] = row.getTotal();
        }
        Map<UUID, CapacitySummary> result = new HashMap<>();
        for (UUID id : ids) {
            long[] value = counts.get(id);
            result.put(id, new CapacitySummary(id, Math.max(0, value[0] - value[1]), value[1], value[2], value[3]));
        }
        return result;
    }

    private void requireHospital(UUID organizationId, UUID hospitalId) {
        hospitals.findByIdAndOrganizationId(hospitalId, organizationId).filter(HospitalFacility::isActive)
                .orElseThrow(FacilityCapacityService::denied);
    }

    private static AccessDeniedException denied() { return new AccessDeniedException("Capacity scope is not available"); }
    private static ActingContext current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ActingContext context) return context;
        throw denied();
    }
}
