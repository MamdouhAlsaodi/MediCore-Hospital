package com.mamtrex.hospital.department;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Public department contract for /api/departments (docs/plan3.md Task 2).
 * Responses are strict allowlists — identity, the resolved branch
 * reference, and business fields only, never timestamps, version, or raw
 * JPA entities. The create request accepts exactly {@code branchId, code,
 * name, specialty, location}: the branch reference is resolved and
 * verified by {@link DepartmentService} (existing AND active), and rows
 * with no branch (the deliberate legacy transition seam) never surface
 * through this contract.
 */
public final class DepartmentDtos {

    private DepartmentDtos() {
    }

    /**
     * Stable department representation: exactly these six fields, no
     * persistence metadata. {@code branchId} is the resolved owning branch
     * of an assigned row.
     */
    public record DepartmentResponse(UUID id, UUID branchId, String code, String name,
                                     String specialty, String location) {

        public static DepartmentResponse from(Department department) {
            return new DepartmentResponse(department.getId(),
                    department.getBranch() == null ? null : department.getBranch().getId(),
                    department.getCode(), department.getName(),
                    department.getSpecialty(), department.getLocation());
        }
    }

    /**
     * Create request allowlist: branchId, code, name, specialty, location
     * — nothing else. Unknown body members are ignored and never persisted;
     * reference verification, activity, trimming, and uniqueness belong to
     * the service.
     */
    public record CreateDepartmentRequest(@NotNull UUID branchId,
                                          @NotBlank String code,
                                          @NotBlank String name,
                                          @NotBlank String specialty,
                                          @NotBlank String location) {
    }
}
