package com.personal.baton.application.identity.port.in;

public interface PasswordResetUseCase {

    void requestPasswordReset(String email);

    void resetPassword(ResetPasswordCommand command);

    record ResetPasswordCommand(String token, String rawPassword) {
        @Override
        public String toString() {
            return "ResetPasswordCommand[token=[REDACTED], rawPassword=[REDACTED]]";
        }
    }
}
