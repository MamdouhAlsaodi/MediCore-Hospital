package com.mamtrex.hospital.reporting;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Thin mapper for GET /api/dashboard (docs/plan2.md Task 5): aggregation
 * lives in {@link DashboardService}. The endpoint stays reachable for any
 * authenticated role through the unchanged SecurityConfig rule and is
 * strictly read-only.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public Map<String, Long> summary() {
        return dashboardService.summary();
    }
}
