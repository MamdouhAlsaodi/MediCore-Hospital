package com.mamtrex.hospital.reporting;

import com.mamtrex.hospital.admission.AdmissionRepository;
import com.mamtrex.hospital.appointment.AppointmentRepository;
import com.mamtrex.hospital.billing.InvoiceRepository;
import com.mamtrex.hospital.emergency.EmergencyVisitRepository;
import com.mamtrex.hospital.patient.PatientRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregation owner for the read-only operations dashboard (docs/plan2.md
 * Task 5). Produces the five legacy total keys plus the flat status-aware
 * count keys in a fixed insertion order, so the JSON shape stays
 * deterministic (a plain {@code Map.of} would not guarantee key order).
 *
 * <p>Counts only — no audit events, no caching, no date windows. Statuses
 * are the server-owned lifecycle strings of Tasks 2–4: admissions
 * ADMITTED/DISCHARGED, emergency visits WAITING/IN_TREATMENT/CLOSED, and
 * invoices DRAFT/ISSUED/PAID/VOID.</p>
 */
@Service
public class DashboardService {

    private final PatientRepository patients;
    private final AppointmentRepository appointments;
    private final AdmissionRepository admissions;
    private final EmergencyVisitRepository emergencyVisits;
    private final InvoiceRepository invoices;

    public DashboardService(PatientRepository patients, AppointmentRepository appointments,
                            AdmissionRepository admissions, EmergencyVisitRepository emergencyVisits,
                            InvoiceRepository invoices) {
        this.patients = patients;
        this.appointments = appointments;
        this.admissions = admissions;
        this.emergencyVisits = emergencyVisits;
        this.invoices = invoices;
    }

    /** Whole-table totals plus status bucket sizes, in contract order. */
    public Map<String, Long> summary() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("patients", patients.count());
        counts.put("appointments", appointments.count());
        counts.put("admissions", admissions.count());
        counts.put("emergencyVisits", emergencyVisits.count());
        counts.put("invoices", invoices.count());
        counts.put("openAdmissions", admissions.countByStatus("ADMITTED"));
        counts.put("activeEmergencyVisits",
                emergencyVisits.countByStatus("WAITING") + emergencyVisits.countByStatus("IN_TREATMENT"));
        counts.put("invoicesDraft", invoices.countByStatus("DRAFT"));
        counts.put("invoicesIssued", invoices.countByStatus("ISSUED"));
        counts.put("invoicesPaid", invoices.countByStatus("PAID"));
        counts.put("invoicesVoid", invoices.countByStatus("VOID"));
        return counts;
    }
}
