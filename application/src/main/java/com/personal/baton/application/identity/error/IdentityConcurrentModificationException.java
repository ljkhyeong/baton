package com.personal.baton.application.identity.error;

/**
 * Signals a stale identity snapshot that can be retried from a fresh transaction.
 */
public class IdentityConcurrentModificationException extends RuntimeException {

    public IdentityConcurrentModificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
