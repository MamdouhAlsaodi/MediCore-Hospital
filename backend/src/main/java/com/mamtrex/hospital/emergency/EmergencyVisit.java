package com.mamtrex.hospital.emergency;
import com.mamtrex.hospital.shared.BaseEntity; import jakarta.persistence.*; import java.util.UUID;
@Entity @Table(name="emergency_visits") public class EmergencyVisit extends BaseEntity {
/*
 * Branch ownership (docs/plan3.md Task 8): stamped by the server from the
 * authenticated acting context at creation and never client input; null is
 * the deliberate legacy seam for pre-Task-8 rows (mirroring Admission),
 * which stay invisible and untouchable through every branch-scoped
 * emergency-visit read and command.
 */
@Column private UUID branchId;
private String patientId; private String arrivalAt; private String triageLevel; private String chiefComplaint; private String status;
protected EmergencyVisit(){}
/** A new visit is owned by the acting branch (server-stamped, never client input). */
public EmergencyVisit(UUID branchId, String patientId, String arrivalAt, String triageLevel, String chiefComplaint, String status){this.branchId=branchId; this.patientId=patientId; this.arrivalAt=arrivalAt; this.triageLevel=triageLevel; this.chiefComplaint=chiefComplaint; this.status=status;}
/** Legacy constructor for pre-Task-8 rows: no ownership, hidden from branch-scoped reads. */
public EmergencyVisit(String patientId, String arrivalAt, String triageLevel, String chiefComplaint, String status){this.patientId=patientId; this.arrivalAt=arrivalAt; this.triageLevel=triageLevel; this.chiefComplaint=chiefComplaint; this.status=status;}
public String getPatientId(){return patientId;} public String getArrivalAt(){return arrivalAt;} public String getTriageLevel(){return triageLevel;} public String getChiefComplaint(){return chiefComplaint;} public String getStatus(){return status;} public UUID getBranchId(){return branchId;}
/**
 * Server-owned lifecycle mutation (docs/plan2.md Task 3): applies an
 * already-validated target state chosen by the EmergencyVisitService
 * transition map. Package-private so no other layer can move the lifecycle.
 */
void changeStatus(String newStatus){this.status=newStatus;}
}
