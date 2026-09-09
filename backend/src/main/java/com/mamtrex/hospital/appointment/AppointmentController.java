package com.mamtrex.hospital.appointment;

import com.mamtrex.hospital.audit.AuditService;
import com.mamtrex.hospital.shared.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * HTTP boundary for /api/appointments (docs/plan1.md Task 3): parses requests
 * and maps entities to {@link AppointmentDtos.AppointmentResponse}. The
 * validated typed scheduledAt/status are persisted unchanged (the entity
 * keeps its String storage until Task 4); shared client-error mapping lives
 * in {@link com.mamtrex.hospital.shared.GlobalExceptionHandler}. Reference
 * validation and creation rules move to a service in docs/plan1.md Task 4;
 * audit recording stays here until that task owns it.
 */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentRepository repo;
    private final AuditService audit;

    public AppointmentController(AppointmentRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @PostMapping
    public AppointmentDtos.AppointmentResponse create(@Valid @RequestBody AppointmentDtos.CreateAppointmentRequest r) {
        var e = repo.save(new Appointment(r.patientId(), r.professionalId(),
                r.scheduledAt().toString(), r.type(), r.status()));
        audit.record("CREATE", "Appointment", e.getId().toString(), "created");
        return AppointmentDtos.AppointmentResponse.from(e);
    }

    @GetMapping
    public List<AppointmentDtos.AppointmentResponse> list() {
        return repo.findAll().stream().map(AppointmentDtos.AppointmentResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AppointmentDtos.AppointmentResponse get(@PathVariable UUID id) {
        return repo.findById(id).map(AppointmentDtos.AppointmentResponse::from)
                .orElseThrow(() -> new NotFoundException("Appointment not found: " + id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        if (!repo.existsById(id)) {
            throw new NotFoundException("Appointment not found: " + id);
        }
        repo.deleteById(id);
        audit.record("DELETE", "Appointment", id.toString(), "deleted");
    }
}
