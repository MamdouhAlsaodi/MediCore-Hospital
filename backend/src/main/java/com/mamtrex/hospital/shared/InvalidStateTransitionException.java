package com.mamtrex.hospital.shared;

/**
 * A requested lifecycle transition is not legal for the record's current
 * state (docs/plan2.md Task 2). Mapped to the shared 409 ApiError by
 * {@link GlobalExceptionHandler}; messages are constructed by the owning
 * service and are always client-safe — never exception or persistence
 * internals.
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
