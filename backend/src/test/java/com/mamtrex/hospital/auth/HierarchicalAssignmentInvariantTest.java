package com.mamtrex.hospital.auth;

import com.mamtrex.hospital.department.Department;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.HospitalFacility;
import com.mamtrex.hospital.organization.HospitalOrganization;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 T020 invariant tests: the exact valid assignment shape for every
 * one of the four scopes, and the invalid shapes that must be inexpressible
 * through the scope-specific factories (specs/005 data-model.md):
 *
 * <ul>
 *   <li>ORGANIZATION — network scope: no hospital, no branch, no department;</li>
 *   <li>HOSPITAL — one fixed hospital: no branch, no department;</li>
 *   <li>BRANCH — one fixed branch of its hospital: hospital derived from the
 *       branch, never a department;</li>
 *   <li>DEPARTMENT — one department: hospital derived through the
 *       department's branch; a branchless department is refused.</li>
 * </ul>
 *
 * Cross-aggregate consistency (a branch really belonging to its hospital's
 * organization) is a persisted-state invariant re-checked against current
 * database state on every use by {@code ActingContextService}; this test
 * pins the in-memory shape invariants the factories own. Pure unit test —
 * no database.
 */
class HierarchicalAssignmentInvariantTest {

    private static UserAccount account(String name) {
        return new UserAccount(name, "disposable-test-hash-not-a-credential", Set.of(Role.ADMIN));
    }

    private static HospitalOrganization network(String code) {
        return new HospitalOrganization(code, "Synthetic Network " + code);
    }

    private static HospitalFacility hospital(HospitalOrganization network, String code) {
        return new HospitalFacility(network, code, "Synthetic Hospital " + code, "Region", "UTC");
    }

    private static Branch branch(HospitalFacility hospital, String code) {
        return new Branch(hospital, code, "Synthetic Branch " + code, "1 Synthetic Way");
    }

    // ------------------------------------------------------- ORGANIZATION

    @Test
    void organizationScopeCarriesNoHospitalBranchOrDepartment() {
        HospitalOrganization network = network("INV-ORG-1");
        ActingAssignment assignment = ActingAssignment.organization(account("org-admin"), network, Role.ADMIN);
        assertEquals(AssignmentScope.ORGANIZATION, assignment.getScope());
        assertEquals(network, assignment.getOrganization());
        assertNull(assignment.getHospital(), "an ORGANIZATION assignment has no fixed hospital");
        assertNull(assignment.getBranch(), "an ORGANIZATION assignment has no fixed branch");
        assertNull(assignment.getDepartment(), "an ORGANIZATION assignment has no department");
        assertTrue(assignment.isEnabled(), "assignments are enabled by default");
    }

    // ----------------------------------------------------------- HOSPITAL

    @Test
    void hospitalScopeCarriesItsHospitalButNoBranchOrDepartment() {
        HospitalOrganization network = network("INV-ORG-2");
        HospitalFacility hospital = hospital(network, "HOSP-INV-1");
        ActingAssignment assignment = ActingAssignment.hospital(account("hosp-admin"), network, Role.ADMIN, hospital);
        assertEquals(AssignmentScope.HOSPITAL, assignment.getScope());
        assertEquals(network, assignment.getOrganization());
        assertEquals(hospital, assignment.getHospital());
        assertNull(assignment.getBranch(), "a HOSPITAL assignment has no fixed branch");
        assertNull(assignment.getDepartment(), "a HOSPITAL assignment has no department");
    }

    @Test
    void hospitalScopeRequiresAHospital() {
        assertThrows(NullPointerException.class,
                () -> ActingAssignment.hospital(account("hosp-null"), network("INV-ORG-3"), Role.ADMIN, null),
                "a HOSPITAL assignment without a hospital is inexpressible");
    }

    // ------------------------------------------------------------- BRANCH

    @Test
    void branchScopeDerivesItsHospitalFromTheBranch() {
        HospitalOrganization network = network("INV-ORG-4");
        HospitalFacility hospital = hospital(network, "HOSP-INV-2");
        Branch fixed = branch(hospital, "BR-INV-1");
        ActingAssignment assignment = ActingAssignment.branch(account("br-user"), network, Role.NURSE, fixed);
        assertEquals(AssignmentScope.BRANCH, assignment.getScope());
        assertEquals(fixed, assignment.getBranch());
        assertEquals(hospital, assignment.getHospital(),
                "the assignment hospital is server-derived from the branch's own hospital");
        assertNull(assignment.getDepartment());
    }

    @Test
    void branchScopeRequiresABranch() {
        assertThrows(NullPointerException.class,
                () -> ActingAssignment.branch(account("br-null"), network("INV-ORG-5"), Role.NURSE, null),
                "a BRANCH assignment without a branch is inexpressible");
    }

    // ---------------------------------------------------------- DEPARTMENT

    @Test
    void departmentScopeDerivesItsHospitalThroughTheDepartmentBranch() {
        HospitalOrganization network = network("INV-ORG-6");
        HospitalFacility hospital = hospital(network, "HOSP-INV-3");
        Branch branchRow = branch(hospital, "BR-INV-2");
        Department department = new Department(branchRow, "DEP-INV-1", "Synthetic Department", "general", "Demo");
        ActingAssignment assignment = ActingAssignment.department(account("dep-user"), network, Role.DOCTOR,
                department);
        assertEquals(AssignmentScope.DEPARTMENT, assignment.getScope());
        assertEquals(department, assignment.getDepartment());
        assertEquals(hospital, assignment.getHospital(),
                "the assignment hospital derives through the department's branch");
        assertNull(assignment.getBranch(), "a DEPARTMENT assignment carries no fixed branch");
    }

    @Test
    void departmentScopeRefusesABranchlessDepartment() {
        // A branchless department (legacy shape) cannot derive a hospital; the
        // branchless row is expressed through a minimal mock — the entity has
        // no mutation seam and none is added.
        Department orphan = org.mockito.Mockito.mock(Department.class);
        org.mockito.Mockito.when(orphan.getBranch()).thenReturn(null);
        assertThrows(IllegalArgumentException.class,
                () -> ActingAssignment.department(account("dep-orphan"), network("INV-ORG-7"), Role.DOCTOR, orphan),
                "a DEPARTMENT assignment whose department has no branch cannot derive a hospital — refused");
    }

    @Test
    void departmentScopeRequiresADepartment() {
        assertThrows(NullPointerException.class,
                () -> ActingAssignment.department(account("dep-null"), network("INV-ORG-8"), Role.DOCTOR, null),
                "a DEPARTMENT assignment without a department is inexpressible");
    }

    // ------------------------------------------------------------- scope enum

    @Test
    void scopeVocabularyContainsExactlyTheFourDescribedScopes() {
        assertEquals(
                Set.of(AssignmentScope.ORGANIZATION, AssignmentScope.HOSPITAL,
                        AssignmentScope.BRANCH, AssignmentScope.DEPARTMENT),
                Set.of(AssignmentScope.values()),
                "ORGANIZATION stays valid (wire compatibility); HOSPITAL joins it");
        assertFalse(AssignmentScope.ORGANIZATION.name().equals("NETWORK"),
                "the ORGANIZATION wire value is preserved verbatim");
    }
}
