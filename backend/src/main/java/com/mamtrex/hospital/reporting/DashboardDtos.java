package com.mamtrex.hospital.reporting;

import java.util.List;
import java.util.UUID;

/**
 * Typed command-center contracts (docs/plan3.md Task 10, §4.7). The records
 * are strict allowlists of server-derived values — no JPA entity, no
 * persistence metadata, and no client input anywhere — and Jackson
 * serializes record components in declaration order, so the JSON key order
 * is deterministic by construction.
 *
 * <p>{@link BranchSummary} is the whole contract of
 * {@code GET /api/dashboard/branch} (and of the retained
 * {@code GET /api/dashboard} compatibility alias): the branch identity from
 * the server-owned branch row, the five whole-row totals and the lifecycle
 * buckets the earlier flat contract carried, the bed counts by canonical
 * occupancy status, today's appointment count from the server's explicit
 * clock, and the simulated invoice status buckets (a financial simulation
 * only). Every number is a non-negative count of rows the acting branch
 * owns; legacy null-ownership rows are invisible, mirroring every other
 * branch-scoped read of this phase.</p>
 *
 * <p>{@link NetworkSummary} is the contract of
 * {@code GET /api/dashboard/network}, issued only to enabled ADMIN contexts
 * with ORGANIZATION scope. The organization totals are exactly the sum over
 * the returned per-branch summaries — nothing else is counted — and
 * {@code branches} lists every active branch of the organization in
 * deterministic code order, including all-zero summaries for branches
 * without records. It is never client aggregation: the server computes and
 * orders everything.</p>
 */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /** Typed summary of one branch's operational state, in deterministic declaration order. */
    public record BranchSummary(
            UUID branchId,
            String branchCode,
            String branchName,
            long patients,
            long appointments,
            long admissions,
            long emergencyVisits,
            long invoices,
            long openAdmissions,
            long activeEmergencyVisits,
            long bedsAvailable,
            long bedsOccupied,
            long bedsMaintenance,
            long bedsOutOfService,
            long todayAppointments,
            long invoicesDraft,
            long invoicesIssued,
            long invoicesPaid,
            long invoicesVoid) {
    }

    /**
     * Typed organization comparison for organization-scoped ADMIN contexts:
     * totals equal to the sum over {@code branches}, plus every active
     * branch's summary in deterministic code order.
     */
    public record NetworkSummary(
            UUID organizationId,
            String organizationName,
            long patients,
            long appointments,
            long admissions,
            long emergencyVisits,
            long invoices,
            long openAdmissions,
            long activeEmergencyVisits,
            long bedsAvailable,
            long bedsOccupied,
            long bedsMaintenance,
            long bedsOutOfService,
            long todayAppointments,
            long invoicesDraft,
            long invoicesIssued,
            long invoicesPaid,
            long invoicesVoid,
            List<BranchSummary> branches) {
    }
}
