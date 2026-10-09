package com.mamtrex.hospital.patient;

/** Lifecycle of one hospital access grant (specs/005 data-model.md). */
public enum PatientAccessStatus {
    /** The hospital may list, read, and mutate the patient. */
    ACTIVE,
    /** Terminal: visibility ends; the row stays as history. */
    REVOKED
}
