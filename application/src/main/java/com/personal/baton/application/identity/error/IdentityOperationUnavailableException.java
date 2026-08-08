package com.personal.baton.application.identity.error;

/**
 * Signals transient contention or infrastructure failure while changing identity state.
 *
 * <p>This is deliberately separate from {@link IdentityConflictException}: a
 * duplicate identity may be a neutral, enumeration-safe registration result,
 * while database contention means that the requested operation did not
 * complete and may be retried later.</p>
 */
public class IdentityOperationUnavailableException extends RuntimeException {

    public IdentityOperationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
