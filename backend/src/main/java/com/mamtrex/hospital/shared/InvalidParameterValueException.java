package com.mamtrex.hospital.shared;

/**
 * A client-supplied value that is well-formed enough to bind but has no
 * valid meaning in the server's domain — e.g. a branch-local time inside a
 * DST gap or an unknown IANA zone. Mapped by the shared handler to the
 * stable 400 Validation Error contract, with a controlled client-safe
 * message and no internal detail.
 */
public class InvalidParameterValueException extends RuntimeException {

    public InvalidParameterValueException(String message) {
        super(message);
    }
}
