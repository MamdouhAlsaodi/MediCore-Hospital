package com.mamtrex.hospital.auth;

/**
 * The exact acting-scope vocabulary of the acting-assignment model
 * (docs/plan3.md Task 3; Phase 5 data-model.md). An assignment acts on the
 * whole organization (network), on one hospital facility, on one fixed
 * branch, or on one department (whose active branch is derived).
 * {@code ORGANIZATION} keeps its exact wire value for compatibility;
 * {@code HOSPITAL} joins it as the hospital-facility scope.
 */
public enum AssignmentScope {
    ORGANIZATION,
    HOSPITAL,
    BRANCH,
    DEPARTMENT
}
