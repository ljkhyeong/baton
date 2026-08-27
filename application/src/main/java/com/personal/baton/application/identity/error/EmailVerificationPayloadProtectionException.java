package com.personal.baton.application.identity.error;

public class EmailVerificationPayloadProtectionException extends RuntimeException {

    private final boolean retryable;

    public EmailVerificationPayloadProtectionException(
            String message,
            boolean retryable
    ) {
        super(message);
        this.retryable = retryable;
    }

    public EmailVerificationPayloadProtectionException(
            String message,
            boolean retryable,
            Throwable cause
    ) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
