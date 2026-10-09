package com.mamtrex.hospital.transfer;

import com.mamtrex.hospital.auth.ActingContext;
import com.mamtrex.hospital.auth.Role;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * The server-side transfer authorization matrix (specs/005 tasks T089,
 * FR-016): only DOCTOR/NURSE/ADMIN of the SOURCE hospital may request,
 * cancel, or start transit; only DOCTOR/ADMIN of the DESTINATION hospital
 * may accept, reject, or complete. Every check consumes the derived
 * {@link ActingContext} — the hospital scope is never client input — and
 * the two refusal classes are deliberately distinct: a wrong ROLE is a
 * generic 403, while a wrong HOSPITAL is the same generic 404 as an
 * unknown transfer, so a foreign hospital can never even discover that a
 * transfer exists.
 */
@Service
public class TransferAuthorizationService {

    /** Source-side operational roles (request/cancel/start transit). */
    private static final Set<Role> SOURCE_ROLES = Set.of(Role.DOCTOR, Role.NURSE, Role.ADMIN);

    /** Destination-side decision roles (accept/reject/complete). */
    private static final Set<Role> DESTINATION_ROLES = Set.of(Role.DOCTOR, Role.ADMIN);

    /** Wrong role for the side being acted on; mapped to a generic 403. */
    public static final class UnauthorizedRoleException extends RuntimeException {
        public UnauthorizedRoleException() {
            super("access denied");
        }
    }

    /**
     * The acting hospital is neither the source nor the destination of the
     * transfer; mapped to the SAME generic 404 as an unknown transfer so a
     * foreign hospital cannot discover the request.
     */
    public static final class ForeignHospitalScopeException extends RuntimeException {
        public ForeignHospitalScopeException() {
            super("Not Found");
        }
    }

    /** The acting role may act on the source side of a transfer. */
    public void requireSourceRole(ActingContext context) {
        if (context == null || !SOURCE_ROLES.contains(context.role())) {
            throw new UnauthorizedRoleException();
        }
    }

    /** The acting role may act on the destination side of a transfer. */
    public void requireDestinationRole(ActingContext context) {
        if (context == null || !DESTINATION_ROLES.contains(context.role())) {
            throw new UnauthorizedRoleException();
        }
    }

    /** The acting hospital must BE the transfer's source hospital. */
    public void requireSourceHospital(ActingContext context, UUID sourceHospitalId) {
        if (context == null || !context.hospitalId().equals(sourceHospitalId)) {
            throw new ForeignHospitalScopeException();
        }
    }

    /** The acting hospital must BE the transfer's destination hospital. */
    public void requireDestinationHospital(ActingContext context, UUID destinationHospitalId) {
        if (context == null || !context.hospitalId().equals(destinationHospitalId)) {
            throw new ForeignHospitalScopeException();
        }
    }

    /** The acting hospital may READ the transfer (source OR destination). */
    public void requireVisibleHospital(ActingContext context, UUID sourceHospitalId,
                                       UUID destinationHospitalId) {
        if (context == null
                || !(context.hospitalId().equals(sourceHospitalId)
                || context.hospitalId().equals(destinationHospitalId))) {
            throw new ForeignHospitalScopeException();
        }
    }

    /** After visibility: an actor of the WRONG SIDE of a visible transfer is a 403, never a 404. */
    public void requireSourceSide(ActingContext context, UUID sourceHospitalId) {
        if (context == null || !context.hospitalId().equals(sourceHospitalId)) {
            throw new UnauthorizedRoleException();
        }
    }

    /** After visibility: an actor of the WRONG SIDE of a visible transfer is a 403, never a 404. */
    public void requireDestinationSide(ActingContext context, UUID destinationHospitalId) {
        if (context == null || !context.hospitalId().equals(destinationHospitalId)) {
            throw new UnauthorizedRoleException();
        }
    }
}
