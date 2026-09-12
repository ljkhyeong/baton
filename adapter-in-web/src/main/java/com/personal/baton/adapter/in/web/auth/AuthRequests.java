package com.personal.baton.adapter.in.web.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthRequests {

    private AuthRequests() {
    }

    public record LocalRegistrationRequest(
            @NotBlank(message = "이메일은 필수입니다")
            @Email(message = "이메일 형식이 올바르지 않습니다")
            @Size(max = 320, message = "이메일은 320자 이하여야 합니다")
            String email,

            @NotBlank(message = "표시 이름은 필수입니다")
            @Size(max = 100, message = "표시 이름은 100자 이하여야 합니다")
            String displayName,

            @Size(max = 2_048, message = "자동 요청 방지 token 길이가 올바르지 않습니다")
            String turnstileToken
    ) {
    }

    public record LocalEmailVerificationRequest(
            @NotBlank(message = "이메일 인증 token은 필수입니다")
            @Size(
                    min = 32,
                    max = 512,
                    message = "이메일 인증 token 길이가 올바르지 않습니다"
            )
            String token,

            @NotBlank(message = "비밀번호는 필수입니다")
            @Size(
                    min = 12,
                    max = 128,
                    message = "비밀번호는 12자 이상 128자 이하여야 합니다"
            )
            String password
    ) {
    }

    public record PasswordResetRequest(
            @NotBlank(message = "이메일은 필수입니다")
            @Email(message = "이메일 형식이 올바르지 않습니다")
            @Size(max = 320, message = "이메일은 320자 이하여야 합니다")
            String email,

            @Size(max = 2_048, message = "자동 요청 방지 token 길이가 올바르지 않습니다")
            String turnstileToken
    ) {
    }

    public record PasswordResetCompletionRequest(
            @NotBlank(message = "재설정 토큰은 필수입니다")
            @Size(min = 32, max = 512, message = "재설정 토큰 길이가 올바르지 않습니다")
            String token,
            @NotBlank(message = "비밀번호는 필수입니다")
            @Size(min = 12, max = 128, message = "비밀번호는 12자 이상 128자 이하여야 합니다")
            String password
    ) {
    }

    public record LocalPasswordChangeRequest(
            @NotBlank(message = "현재 비밀번호는 필수입니다")
            @Size(max = 128, message = "현재 비밀번호는 128자 이하여야 합니다")
            String currentPassword,

            @NotBlank(message = "새 비밀번호는 필수입니다")
            @Size(
                    min = 12,
                    max = 128,
                    message = "새 비밀번호는 12자 이상 128자 이하여야 합니다"
            )
            String newPassword
    ) {
    }
}
