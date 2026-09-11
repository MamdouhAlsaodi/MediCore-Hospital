package com.mamtrex.hospital.staff;

import java.util.UUID;

/**
 * Public staff contract for /api/staff (docs/plan1.md Task 3, docs/plan3.md
 * Task 4). Staff records are the professional directory behind appointment
 * professional selection, so responses expose selection data only and never
 * persistence internals (version, createdAt, updatedAt) or any mutable JPA
 * entity. Since Task 4 the response exposes the owning {@code branchId};
 * the create request accepts no branch input — ownership derives from the
 * acting context alone, and unassigned legacy rows stay undisclosed.
 */
public final class StaffMemberDtos {

    private StaffMemberDtos() {}

    /** Stable public staff representation returned by every staff route. */
    public record StaffMemberResponse(UUID id, UUID branchId, String employeeCode, String fullName,
                                      String profession, String licenseNumber, String department) {

        public static StaffMemberResponse from(StaffMember s) {
            return new StaffMemberResponse(s.getId(), s.getBranch() == null ? null : s.getBranch().getId(),
                    s.getEmployeeCode(), s.getFullName(),
                    s.getProfession(), s.getLicenseNumber(), s.getDepartment());
        }
    }
}
