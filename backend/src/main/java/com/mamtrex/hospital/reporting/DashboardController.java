package com.mamtrex.hospital.reporting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP boundary for the command-center reads (docs/plan3.md Task 10):
 * parsing and DTO mapping only — scope, aggregation, ordering, and the
 * network-view ADMIN rule live in {@link DashboardService}, and shared
 * client-error mapping lives in
 * {@link com.mamtrex.hospital.shared.GlobalExceptionHandler}. The route
 * family keeps its SecurityConfig rule ({@code /api/dashboard/**
 * authenticated()}), so the branch summary stays reachable for every
 * authenticated role while the network summary enforces its stricter
 * organization-scoped ADMIN contract in the service.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** Typed summary of the acting branch derived from the verified acting context. */
    @GetMapping("/branch")
    public DashboardDtos.BranchSummary branch() {
        return dashboardService.branchSummary();
    }

    /**
     * Organization totals plus the deterministic per-branch summaries, for
     * enabled ADMIN contexts with ORGANIZATION scope only; every other
     * authenticated context receives the ordinary denial.
     */
    @GetMapping("/network")
    public DashboardDtos.NetworkSummary network() {
        return dashboardService.networkSummary();
    }

    /**
     * Deprecated compatibility alias (docs/plan3.md Task 10): during
     * Phase 3 this retained path answers with exactly the acting branch's
     * typed summary — the same body as {@link #branch()} — and never falls
     * back to the pre-Task 10 whole-table flat counts. It exists so the
     * established Phase 3 consumers keep working until a later packet
     * migrates them to {@code /api/dashboard/branch}; new clients must call
     * the explicit branch path.
     */
    @Deprecated
    @GetMapping
    public DashboardDtos.BranchSummary branchAlias() {
        return dashboardService.branchSummary();
    }
}
