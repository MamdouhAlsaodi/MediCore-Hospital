package com.mamtrex.hospital.auth;

/**
 * The exact acting-scope vocabulary of the acting-assignment model
 * (docs/plan3.md Task 3). An assignment acts on the whole organization, on
 * one fixed branch, or on one department (whose active branch is derived).
 */
public enum AssignmentScope {
    ORGANIZATION,
    BRANCH,
    DEPARTMENT
}
