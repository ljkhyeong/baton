package com.personal.baton.application.identity.error;

public class EmailVerificationDeliveryUnavailableException extends RuntimeException {

    public EmailVerificationDeliveryUnavailableException(String message) {
        super(message);
    }

    public EmailVerificationDeliveryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
