package com.mamtrex.hospital.staff;

import java.util.UUID;

/**
 * Public staff contract for /api/staff (docs/plan1.md Task 3). Staff records
 * are the professional directory behind appointment professional selection,
 * so responses expose selection data only and never persistence internals
 * (version, createdAt, updatedAt) or any mutable JPA entity.
 */
public final class StaffMemberDtos {

    private StaffMemberDtos() {}

    /** Stable public staff representation returned by every staff route. */
    public record StaffMemberResponse(UUID id, String employeeCode, String fullName,
                                      String profession, String licenseNumber, String department) {

        public static StaffMemberResponse from(StaffMember s) {
            return new StaffMemberResponse(s.getId(), s.getEmployeeCode(), s.getFullName(),
                    s.getProfession(), s.getLicenseNumber(), s.getDepartment());
        }
    }
}
