package com.mamtrex.hospital.patient;

/** Provenance of one hospital access grant (specs/005 data-model.md). */
public enum PatientAccessSource {
    /** Backfilled by V6 for every pre-existing network patient. */
    LEGACY_MIGRATION,
    /** Created with the patient at its registering hospital (T069). */
    LOCAL_REGISTRATION,
    /** Created when a transfer is accepted (US4 seam; carries the transfer id). */
    TRANSFER_ACCEPTED
}
