package com.mamtrex.hospital.staff;

import com.mamtrex.hospital.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One explicit dated availability interval for one professional in one
 * branch (docs/plan3.md Task 9, §4.6). Availability is deliberately dated —
 * no recurring calendar, leave, payroll, credentialing, or
 * profession-based clinical-eligibility concept is modeled here.
 *
 * <p>The branch and professional references follow the
 * {@code AdmissionBedAssignment} precedent: app-owned UUID columns resolved
 * through repositories, never JPA relations, so no entity graph leaks
 * outward and no FK lifecycle couples availability rows to staff deletion.
 * The time values are typed {@link LocalDateTime} columns consistent with
 * the current API/JPA UTC/ISO contract, and every interval is half-open
 * {@code [startsAt, endsAt)}: an interval ending exactly when another
 * starts does not overlap it.</p>
 */
@Entity
@Table(name = "staff_availability")
public class StaffAvailability extends BaseEntity {

    @Column(nullable = false)
    private UUID branchId;

    @Column(nullable = false)
    private UUID staffMemberId;

    @Column(nullable = false)
    private LocalDateTime startsAt;

    @Column(nullable = false)
    private LocalDateTime endsAt;

    protected StaffAvailability() {}

    public StaffAvailability(UUID branchId, UUID staffMemberId, LocalDateTime startsAt, LocalDateTime endsAt) {
        this.branchId = branchId;
        this.staffMemberId = staffMemberId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public UUID getBranchId() {
        return branchId;
    }

    public UUID getStaffMemberId() {
        return staffMemberId;
    }

    public LocalDateTime getStartsAt() {
        return startsAt;
    }

    public LocalDateTime getEndsAt() {
        return endsAt;
    }
}
