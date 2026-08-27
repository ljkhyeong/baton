package com.personal.baton.application.roundauth.error;

public class ParticipationGrantUnavailableException extends RuntimeException {

    public ParticipationGrantUnavailableException(String message) {
        super(message);
    }

    public ParticipationGrantUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
