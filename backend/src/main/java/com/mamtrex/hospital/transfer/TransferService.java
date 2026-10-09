package com.mamtrex.hospital.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mamtrex.hospital.admission.Admission;
import com.mamtrex.hospital.admission.AdmissionBedAssignment;
import com.mamtrex.hospital.admission.AdmissionBedAssignmentRepository;
import com.mamtrex.hospital.admission.AdmissionRepository;
import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.bed.Bed;
import com.mamtrex.hospital.bed.BedRepository;
import com.mamtrex.hospital.idempotency.IdempotencyService;
import com.mamtrex.hospital.idempotency.IdempotencyRecordRepository;
import com.mamtrex.hospital.organization.Branch;
import com.mamtrex.hospital.organization.BranchRepository;
import com.mamtrex.hospital.organization.HospitalFacility;
import com.mamtrex.hospital.organization.HospitalFacilityRepository;
import com.mamtrex.hospital.patient.Patient;
import com.mamtrex.hospital.patient.PatientAccessSource;
import com.mamtrex.hospital.patient.PatientAccessStatus;
import com.mamtrex.hospital.patient.PatientHospitalAccess;
import com.mamtrex.hospital.patient.PatientHospitalAccessRepository;
import com.mamtrex.hospital.patient.PatientRepository;
import com.mamtrex.hospital.shared.InvalidParameterValueException;
import com.mamtrex.hospital.shared.InvalidStateTransitionException;
import com.mamtrex.hospital.shared.NotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The inter-hospital transfer workflow (specs/005 US4, tasks T092–T096).
 * Every lifecycle command runs in ONE transaction that: validates the
 * derived acting context (server-side role AND hospital matrix), guards the
 * optimistic expected version, applies the aggregate state machine, applies
 * the atomic side effects (reservation, access grant, admission handoff,
 * bed occupancy), writes exactly one transfer-context audit row, and
 * commits the idempotency outcome — so a failure leaves no partial rows
 * and no false success audit, and a committed replay returns the stored
 * outcome.
 *
 * <p>Source ownership is server-derived: the create command takes the
 * source hospital/branch from the acting context, never from the body.
 * Foreign hospitals read a generic 404 and never discover a transfer.
 * Concurrency backstop: the accept command serializes on a pessimistic
 * lock over the bed's ACTIVE reservation, and the V7 partial unique index
 * (one ACTIVE reservation per bed) is the database-level guarantee that
 * exactly one racing accept wins.
 */
@Service
public class TransferService {

    private static final int MAX_LIST_SIZE = 200;

    private final TransferRepository transfers;
    private final TransferBedReservationRepository reservations;
    private final IdempotencyService idempotency;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final ObjectMapper objectMapper;
    private final TransferAuthorizationService authorization;
    private final AuditService audit;
    private final HospitalFacilityRepository hospitals;
    private final BranchRepository branches;
    private final BedRepository beds;
    private final PatientRepository patients;
    private final PatientHospitalAccessRepository accessGrants;
    private final AdmissionRepository admissions;
    private final AdmissionBedAssignmentRepository bedAssignments;

    public TransferService(TransferRepository transfers,
                           TransferBedReservationRepository reservations,
                           IdempotencyService idempotency,
                           TransferAuthorizationService authorization,
                           AuditService audit,
                           HospitalFacilityRepository hospitals,
                           BranchRepository branches,
                           BedRepository beds,
                           PatientRepository patients,
                           PatientHospitalAccessRepository accessGrants,
                           AdmissionRepository admissions,
                           AdmissionBedAssignmentRepository bedAssignments,
                           IdempotencyRecordRepository idempotencyRecords,
                           ObjectMapper objectMapper) {
        this.transfers = transfers;
        this.reservations = reservations;
        this.idempotency = idempotency;
        this.idempotencyRecords = idempotencyRecords;
        this.objectMapper = objectMapper;
        this.authorization = authorization;
        this.audit = audit;
        this.hospitals = hospitals;
        this.branches = branches;
        this.beds = beds;
        this.patients = patients;
        this.accessGrants = accessGrants;
        this.admissions = admissions;
        this.bedAssignments = bedAssignments;
    }

    /** A workflow outcome: the view plus whether it was an idempotent replay. */
    public record Outcome(TransferDtos.TransferView view, boolean replay, Integer replayHttpStatus) {
    }

    // ------------------------------------------------------------------ T092

    /**
     * REQUESTED creation. The source hospital/branch derive from the acting
     * context; the client supplies only the patient, the source admission,
     * the destination hospital, and one bounded reason code.
     */
    @Transactional
    public Outcome create(ActingContext actor, String idempotencyKey,
                          TransferDtos.CreateTransferRequest request) {
        String fingerprint = idempotency.sha256Fingerprint("requestTransfer|"
                + request.patientId() + "|" + request.sourceAdmissionId() + "|"
                + request.destinationHospitalId() + "|" + request.reasonCode());
        // Serialize same-assignment creates before the absent-key check, including on
        // H2 where the production unique index is not present in the create-drop schema.
        authorization.requireSourceRole(actor);
        idempotencyRecords.lockAssignment(actor.assignmentId())
                .orElseThrow(() -> new NotFoundException("Not Found"));
        var idem = idempotency.begin(actor.assignmentId(), "requestTransfer", idempotencyKey,
                fingerprint, "TransferRequest");
        if (idem.replay()) {
            return replayOf(idem.record(), idempotencyKey);
        }
        try {
            authorization.requireSourceRole(actor);

            String reasonCode = validatedReasonCode(request.reasonCode());
            if (request.patientId() == null || request.sourceAdmissionId() == null
                    || request.destinationHospitalId() == null) {
                throw new InvalidParameterValueException(
                        "patientId, sourceAdmissionId, and destinationHospitalId are required");
            }

            // The patient must be visible to the acting hospital (FR-013): no
            // active grant -> same generic 404 as an unknown patient.
            if (!accessGrants.existsByPatientIdAndHospitalIdAndStatus(
                    request.patientId(), actor.hospitalId(), PatientAccessStatus.ACTIVE)) {
                throw new NotFoundException("Not Found");
            }
            Patient patient = patients.findById(request.patientId()).orElseThrow(() -> new NotFoundException("Not Found"));

            HospitalFacility sourceHospital = hospitals.findById(actor.hospitalId())
                    .orElseThrow(() -> new NotFoundException("Not Found"));
            HospitalFacility destination = hospitals.findById(request.destinationHospitalId())
                    .orElseThrow(() -> new NotFoundException("Not Found"));
            if (!destination.isActive()
                    || !destination.getOrganization().getId().equals(actor.organizationId())
                    || destination.getId().equals(sourceHospital.getId())) {
                throw new InvalidParameterValueException(
                        "The destination must be another active hospital of the same organization");
            }

            // The source admission must be an ADMITTED admission of the acting
            // branch for exactly this patient.
            Admission admission = admissions.findByIdAndBranchId(request.sourceAdmissionId(), actor.branchId())
                    .orElseThrow(() -> new NotFoundException("Not Found"));
            if (!patient.getId().toString().equals(admission.getPatientId())
                    || !Admission.STATUS_ADMITTED.equals(admission.getStatus())) {
                throw new NotFoundException("Not Found");
            }

            TransferRequest transfer = new TransferRequest(
                    "TR-" + UUID.randomUUID(), actor.organizationId(), patient.getId(),
                    sourceHospital.getId(), actor.branchId(), admission.getId(),
                    destination.getId(), null, reasonCode, actor.assignmentId(), Instant.now());
            transfers.save(transfer);

            audit.recordTransfer("TRANSFER_REQUEST", "TransferRequest", transfer.getId().toString(),
                    "transfer requested (" + reasonCode + ")", transfer.getId(),
                    transfer.getSourceHospitalId(), transfer.getDestinationHospitalId());
            return storedOutcome(idem.record(), transfer, 201);
        } catch (RuntimeException failure) {
            // The idempotency row is part of the same transaction: a failed
            // mutation rolls back its begin() row too, so the key stays unused.
            throw failure;
        }
    }

    /** Scoped list: only transfers whose source OR destination hospital is the acting hospital. */
    @Transactional(readOnly = true)
    public List<TransferDtos.TransferView> list(ActingContext actor, TransferStatus status) {
        Pageable page = PageRequest.of(0, MAX_LIST_SIZE, Sort.by(Sort.Direction.DESC, "requestedAt"));
        return transfers.findVisible(actor.organizationId(), actor.hospitalId(), status, page).stream()
                .map(TransferDtos.TransferView::of)
                .toList();
    }

    /** Scoped read: source or destination hospital only; a foreign hospital gets the generic 404. */
    @Transactional(readOnly = true)
    public TransferDtos.TransferView get(ActingContext actor, UUID transferId) {
        return TransferDtos.TransferView.of(loadVisible(actor, transferId));
    }

    // ------------------------------------------------------------------ T093

    /** ACCEPTED: destination-side validation, atomic bed reservation, patient access grant. */
    @Transactional
    public Outcome accept(ActingContext actor, String idempotencyKey, UUID transferId,
                          TransferDtos.AcceptTransferRequest request) {
        String fingerprint = idempotency.sha256Fingerprint("acceptTransfer|" + transferId + "|"
                + request.destinationBranchId() + "|" + request.destinationBedId() + "|"
                + request.expectedVersion());
        authorization.requireDestinationRole(actor);
        // Lock the transfer row BEFORE the idempotency begin: two racing
        // calls with the same key serialize here, and the loser's begin()
        // sees the committed record and replays the stored outcome instead
        // of losing a database insert race at commit time.
        TransferRequest transfer = loadVisibleForUpdate(actor, transferId);
        var idem = idempotency.begin(actor.assignmentId(), "acceptTransfer", idempotencyKey,
                fingerprint, "TransferRequest");
        if (idem.replay()) {
            return replayOf(idem.record(), idempotencyKey);
        }
        authorization.requireDestinationSide(actor, transfer.getDestinationHospitalId());
        requireExpectedVersion(transfer, request.expectedVersion());

        Branch destinationBranch = branches.findById(request.destinationBranchId())
                .filter(branch -> branch.isActive()
                        && transfer.getDestinationHospitalId().equals(branch.getHospital().getId()))
                .orElseThrow(() -> new NotFoundException("Not Found"));
        Bed bed = beds.findByIdAndBranchIdForUpdate(request.destinationBedId(), destinationBranch.getId())
                .orElseThrow(() -> new NotFoundException("Not Found"));
        if (!Bed.STATUS_AVAILABLE.equals(bed.getOccupancyStatus()) || bedAssignments.existsByBedId(bed.getId())) {
            throw new InvalidStateTransitionException(
                    "The destination bed is not available for reservation");
        }
        // Serialize racing accepts of the same bed; the V7 partial unique
        // index stays the database backstop.
        reservations.findActiveByBedIdForUpdate(bed.getId()).ifPresent(existing -> {
            throw new InvalidStateTransitionException(
                    "The destination bed is already reserved for another transfer");
        });

        transfer.accept(destinationBranch.getId(), bed.getId(), Instant.now());
        // V7 links (transfer_id, bed_id) to the accepted transfer's destination bed.
        // Flush that parent update before inserting its reservation: Hibernate's
        // insert ordering otherwise checks the composite FK against REQUESTED.
        transfers.flush();
        reservations.save(new TransferBedReservation(transfer.getId(), bed.getId(), Instant.now()));

        // The destination hospital gains patient access exactly once (FR-015);
        // a rollback removes no pre-existing grant.
        if (!accessGrants.existsByPatientIdAndHospitalIdAndStatus(
                transfer.getPatientId(), transfer.getDestinationHospitalId(), PatientAccessStatus.ACTIVE)) {
            Patient patient = patients.findById(transfer.getPatientId()).orElseThrow(() -> new NotFoundException("Not Found"));
            HospitalFacility destinationHospital =
                    hospitals.findById(transfer.getDestinationHospitalId()).orElseThrow(() -> new NotFoundException("Not Found"));
            accessGrants.save(new PatientHospitalAccess(patient, destinationHospital,
                    PatientAccessSource.TRANSFER_ACCEPTED, transfer.getId()));
        }

        audit.recordTransfer("TRANSFER_ACCEPT", "TransferRequest", transfer.getId().toString(),
                "transfer accepted with bed reservation", transfer.getId(),
                transfer.getSourceHospitalId(), transfer.getDestinationHospitalId());
        return storedOutcome(idem.record(), transfer, 200);
    }

    // ------------------------------------------------------------------ T094

    /** REJECTED (terminal): destination-side decision, no reservation exists yet. */
    @Transactional
    public Outcome reject(ActingContext actor, String idempotencyKey, UUID transferId,
                          TransferDtos.TransitionReasonRequest request) {
        String fingerprint = idempotency.sha256Fingerprint(
                "rejectTransfer|" + transferId + "|" + request.reasonCode() + "|" + request.expectedVersion());
        TransferRequest transfer = loadVisibleForUpdate(actor, transferId);
        var idem = idempotency.begin(actor.assignmentId(), "rejectTransfer", idempotencyKey,
                fingerprint, "TransferRequest");
        if (idem.replay()) {
            return replayOf(idem.record(), idempotencyKey);
        }
        authorization.requireDestinationRole(actor);
        authorization.requireDestinationSide(actor, transfer.getDestinationHospitalId());
        requireExpectedVersion(transfer, request.expectedVersion());
        validatedReasonCode(request.reasonCode());
        transfer.reject(Instant.now());
        audit.recordTransfer("TRANSFER_REJECT", "TransferRequest", transfer.getId().toString(),
                "transfer rejected (" + validatedReasonCode(request.reasonCode()) + ")",
                transfer.getId(), transfer.getSourceHospitalId(), transfer.getDestinationHospitalId());
        return storedOutcome(idem.record(), transfer, 200);
    }

    /** CANCELLED (terminal): source-side; releases this transfer's own reservation exactly. */
    @Transactional
    public Outcome cancel(ActingContext actor, String idempotencyKey, UUID transferId,
                          TransferDtos.TransitionReasonRequest request) {
        String fingerprint = idempotency.sha256Fingerprint(
                "cancelTransfer|" + transferId + "|" + request.reasonCode() + "|" + request.expectedVersion());
        TransferRequest transfer = loadVisibleForUpdate(actor, transferId);
        var idem = idempotency.begin(actor.assignmentId(), "cancelTransfer", idempotencyKey,
                fingerprint, "TransferRequest");
        if (idem.replay()) {
            return replayOf(idem.record(), idempotencyKey);
        }
        authorization.requireSourceRole(actor);
        authorization.requireSourceSide(actor, transfer.getSourceHospitalId());
        requireExpectedVersion(transfer, request.expectedVersion());
        validatedReasonCode(request.reasonCode());
        Instant now = Instant.now();
        reservations.findByTransferIdForUpdate(transfer.getId())
                .filter(reservation -> reservation.getStatus() == ReservationStatus.ACTIVE)
                .ifPresent(reservation -> reservation.release(now));
        transfer.cancel(now);
        audit.recordTransfer("TRANSFER_CANCEL", "TransferRequest", transfer.getId().toString(),
                "transfer cancelled (" + validatedReasonCode(request.reasonCode()) + ")",
                transfer.getId(), transfer.getSourceHospitalId(), transfer.getDestinationHospitalId());
        return storedOutcome(idem.record(), transfer, 200);
    }

    // ------------------------------------------------------------------ T095

    /**
     * IN_TRANSIT: source-side; performs the source admission/bed handoff —
     * the source admission is discharged and its bed assignment released —
     * inside the same transaction as the state change.
     */
    @Transactional
    public Outcome startTransit(ActingContext actor, String idempotencyKey, UUID transferId) {
        String fingerprint = idempotency.sha256Fingerprint("startTransferTransit|" + transferId);
        TransferRequest transfer = loadVisibleForUpdate(actor, transferId);
        var idem = idempotency.begin(actor.assignmentId(), "startTransferTransit", idempotencyKey,
                fingerprint, "TransferRequest");
        if (idem.replay()) {
            return replayOf(idem.record(), idempotencyKey);
        }
        authorization.requireSourceRole(actor);
        authorization.requireSourceSide(actor, transfer.getSourceHospitalId());

        if (transfer.getSourceAdmissionId() != null) {
            Admission admission = admissions
                    .findByIdAndBranchIdForUpdate(transfer.getSourceAdmissionId(), transfer.getSourceBranchId())
                    .orElseThrow(() -> new NotFoundException("Not Found"));
            if (!transfer.getPatientId().toString().equals(admission.getPatientId())) {
                throw new InvalidStateTransitionException("Source admission no longer belongs to the transfer patient");
            }
            if (!Admission.STATUS_ADMITTED.equals(admission.getStatus())) {
                throw new InvalidStateTransitionException("Source admission is already discharged");
            }
            var assignment = bedAssignments.findByAdmissionId(admission.getId());
            if (assignment.isPresent()) {
                Bed bed = beds.findByIdAndBranchIdForUpdate(assignment.orElseThrow().getBedId(),
                        transfer.getSourceBranchId()).orElseThrow(() -> new InvalidStateTransitionException(
                        "Source bed assignment does not belong to this branch"));
                if (!Bed.STATUS_OCCUPIED.equals(bed.getOccupancyStatus())) {
                    throw new InvalidStateTransitionException("Source assignment bed is not admission-occupied");
                }
                bed.releaseByAdmission();
                bedAssignments.delete(assignment.orElseThrow());
            }
            admission.dischargeAt(Instant.now());
        }

        transfer.startTransit(Instant.now());
        audit.recordTransfer("TRANSFER_START_TRANSIT", "TransferRequest", transfer.getId().toString(),
                "transfer moved in transit", transfer.getId(),
                transfer.getSourceHospitalId(), transfer.getDestinationHospitalId());
        return storedOutcome(idem.record(), transfer, 200);
    }

    // ------------------------------------------------------------------ T096

    /**
     * COMPLETED (terminal): destination-side; creates the destination
     * admission, consumes the reservation, and occupies the reserved bed in
     * ONE transaction (FR-019). The same network patient identity is kept.
     */
    @Transactional
    public Outcome complete(ActingContext actor, String idempotencyKey, UUID transferId) {
        String fingerprint = idempotency.sha256Fingerprint("completeTransfer|" + transferId);
        TransferRequest transfer = loadVisibleForUpdate(actor, transferId);
        var idem = idempotency.begin(actor.assignmentId(), "completeTransfer", idempotencyKey,
                fingerprint, "TransferRequest");
        if (idem.replay()) {
            return replayOf(idem.record(), idempotencyKey);
        }
        authorization.requireDestinationRole(actor);
        authorization.requireDestinationSide(actor, transfer.getDestinationHospitalId());

        // All bed-consuming paths lock bed -> reservation. Taking the reservation
        // first would invert BedService's order and deadlock with a concurrent
        // bed status change or delete.
        if (transfer.getDestinationBedId() == null || transfer.getDestinationBranchId() == null) {
            throw new InvalidStateTransitionException("An accepted transfer with a destination bed is required");
        }
        Bed bed = beds.findByIdAndBranchIdForUpdate(transfer.getDestinationBedId(),
                transfer.getDestinationBranchId())
                .orElseThrow(() -> new InvalidStateTransitionException("Reserved bed is no longer in the destination branch"));
        TransferBedReservation reservation = reservations.findByTransferIdForUpdate(transfer.getId())
                .filter(res -> res.getStatus() == ReservationStatus.ACTIVE)
                .orElseThrow(() -> new InvalidStateTransitionException(
                        "An accepted transfer with an active reservation is required for completion"));

        if (!reservation.getBedId().equals(transfer.getDestinationBedId())
                || !Bed.STATUS_AVAILABLE.equals(bed.getOccupancyStatus())
                || bedAssignments.existsByBedId(bed.getId())) {
            throw new InvalidStateTransitionException("Reserved bed is no longer available for admission");
        }
        transfer.complete(Instant.now());
        reservation.consume();
        bed.markOccupiedByAdmission();
        Admission destinationAdmission = admissions.save(new Admission(transfer.getDestinationBranchId(),
                transfer.getPatientId().toString(), Instant.now(), transfer.getReasonCode()));
        bedAssignments.save(new AdmissionBedAssignment(destinationAdmission.getId(), bed.getId()));

        audit.recordTransfer("TRANSFER_COMPLETE", "TransferRequest", transfer.getId().toString(),
                "transfer completed at destination", transfer.getId(),
                transfer.getSourceHospitalId(), transfer.getDestinationHospitalId());
        return storedOutcome(idem.record(), transfer, 200);
    }

    // ------------------------------------------------------------- internals

    /** Organization-scoped, visibility-guarded load; foreign hospitals get the generic 404. */
    private TransferRequest loadVisible(ActingContext actor, UUID transferId) {
        TransferRequest transfer = transfers.findByIdAndOrganizationId(transferId, actor.organizationId())
                .orElseThrow(() -> new NotFoundException("Not Found"));
        authorization.requireVisibleHospital(actor, transfer.getSourceHospitalId(),
                transfer.getDestinationHospitalId());
        return transfer;
    }

    /** The locked variant every transition command uses before its idempotency begin. */
    private TransferRequest loadVisibleForUpdate(ActingContext actor, UUID transferId) {
        TransferRequest transfer = transfers
                .findByIdAndOrganizationIdForUpdate(transferId, actor.organizationId())
                .orElseThrow(() -> new NotFoundException("Not Found"));
        authorization.requireVisibleHospital(actor, transfer.getSourceHospitalId(),
                transfer.getDestinationHospitalId());
        return transfer;
    }

    /** An optimistic-version mismatch is a typed conflict, never a silent overwrite. */
    private static void requireExpectedVersion(TransferRequest transfer, long expectedVersion) {
        if (transfer.getVersion() != expectedVersion) {
            throw new InvalidStateTransitionException(
                    "The transfer was modified concurrently: expected version mismatch");
        }
    }

    private static String validatedReasonCode(String reasonCode) {
        if (reasonCode == null || !TransferDtos.ALLOWED_REASON_CODES.contains(reasonCode)) {
            throw new InvalidParameterValueException(
                    "reasonCode must be one of the bounded operational codes: "
                            + String.join(", ", TransferDtos.ALLOWED_REASON_CODES.stream().sorted().toList()));
        }
        return reasonCode;
    }

    private Outcome storedOutcome(com.mamtrex.hospital.idempotency.IdempotencyRecord record,
                                  TransferRequest transfer, int status) {
        var view = TransferDtos.TransferView.of(transfer);
        try {
            idempotency.complete(record, transfer.getId(), status, objectMapper.writeValueAsString(view));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize transfer outcome", failure);
        }
        return new Outcome(view, false, null);
    }

    /** A replay is the exact committed response, not a read of mutable transfer state. */
    private Outcome replayOf(com.mamtrex.hospital.idempotency.IdempotencyRecord record,
                             String idempotencyKey) {
        if (record.getResourceId() == null || record.getResponseSnapshot() == null
                || record.getHttpStatus() == null) {
            throw new InvalidStateTransitionException(
                    "Idempotency-Key is already in use by an in-flight request");
        }
        try {
            return new Outcome(objectMapper.readValue(record.getResponseSnapshot(),
                    TransferDtos.TransferView.class), true, record.getHttpStatus());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot deserialize stored transfer outcome", failure);
        }
    }
}
