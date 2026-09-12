package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.auth.AuthRequests.LocalEmailVerificationRequest;
import com.personal.baton.adapter.in.web.auth.AuthRequests.LocalRegistrationRequest;
import com.personal.baton.adapter.in.web.auth.AuthRequests.PasswordResetRequest;
import com.personal.baton.adapter.in.web.auth.AuthRequests.PasswordResetCompletionRequest;
import com.personal.baton.adapter.in.web.auth.AuthResponses.PasswordResetRequestResponse;
import com.personal.baton.adapter.in.web.auth.AuthResponses.AuthenticatedSessionResponse;
import com.personal.baton.adapter.in.web.auth.AuthResponses.AuthProvidersResponse;
import com.personal.baton.adapter.in.web.auth.AuthResponses.CsrfResponse;
import com.personal.baton.adapter.in.web.auth.AuthResponses.LocalRegistrationResponse;
import com.personal.baton.adapter.in.web.auth.AuthResponses.UnauthenticatedSessionResponse;
import com.personal.baton.adapter.in.web.config.AuthFeatureProperties;
import com.personal.baton.adapter.in.web.config.SocialLoginProviderCatalog;
import com.personal.baton.application.identity.error.EmailVerificationDeliveryUnavailableException;
import com.personal.baton.application.identity.error.IdentityConflictException;
import com.personal.baton.application.identity.port.in.HumanVerificationUseCase;
import com.personal.baton.application.identity.port.in.HumanVerificationUseCase.Action;
import com.personal.baton.application.identity.port.in.HumanVerificationUseCase.HumanVerificationCommand;
import com.personal.baton.application.identity.port.in.PasswordResetUseCase;
import com.personal.baton.application.identity.port.in.PasswordResetUseCase.ResetPasswordCommand;
import com.personal.baton.application.identity.port.in.RegisterLocalAccountUseCase;
import com.personal.baton.application.identity.port.in.RegisterLocalAccountUseCase.RegisterLocalAccountCommand;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase.VerifyLocalEmailCommand;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public static final String CSRF_PATH = "/api/v1/auth/csrf";
    public static final String SESSION_PATH = "/api/v1/auth/session";
    public static final String PROVIDERS_PATH = "/api/v1/auth/providers";
    public static final String LOCAL_REGISTRATIONS_PATH =
            "/api/v1/auth/local/registrations";
    public static final String LOCAL_EMAIL_VERIFICATIONS_PATH =
            "/api/v1/auth/local/email-verifications";
    public static final String LOCAL_SESSION_PATH = "/api/v1/auth/local/session";
    public static final String LOGOUT_PATH = "/api/v1/auth/logout";
    public static final String PASSWORD_RESET_REQUESTS_PATH = "/api/v1/auth/local/password-reset-requests";
    public static final String PASSWORD_RESETS_PATH = "/api/v1/auth/local/password-resets";

    private final RegisterLocalAccountUseCase registerLocalAccountUseCase;
    private final VerifyLocalEmailUseCase verifyLocalEmailUseCase;
    private final PasswordResetUseCase passwordResetUseCase;
    private final ObjectProvider<SocialLoginProviderCatalog> socialLoginProviderCatalogProvider;
    private final AuthRateLimiter authRateLimiter;
    private final AuthFeatureProperties authFeatureProperties;
    private final HumanVerificationUseCase humanVerificationUseCase;

    public AuthController(
            RegisterLocalAccountUseCase registerLocalAccountUseCase,
            VerifyLocalEmailUseCase verifyLocalEmailUseCase,
            PasswordResetUseCase passwordResetUseCase,
            ObjectProvider<SocialLoginProviderCatalog> socialLoginProviderCatalogProvider,
            AuthRateLimiter authRateLimiter,
            AuthFeatureProperties authFeatureProperties,
            HumanVerificationUseCase humanVerificationUseCase
    ) {
        this.registerLocalAccountUseCase = registerLocalAccountUseCase;
        this.verifyLocalEmailUseCase = verifyLocalEmailUseCase;
        this.passwordResetUseCase = passwordResetUseCase;
        this.socialLoginProviderCatalogProvider = socialLoginProviderCatalogProvider;
        this.authRateLimiter = authRateLimiter;
        this.authFeatureProperties = authFeatureProperties;
        this.humanVerificationUseCase = humanVerificationUseCase;
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(CsrfToken csrfToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(
                        csrfToken.getHeaderName(),
                        csrfToken.getToken()
                ));
    }

    @GetMapping("/session")
    public ResponseEntity<Object> session(
            @AuthenticationPrincipal AuthenticatedAccountPrincipal principal,
            CsrfToken csrfToken
    ) {
        if (principal == null) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new UnauthenticatedSessionResponse());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new AuthenticatedSessionResponse(
                        principal.accountId(),
                        csrfToken.getHeaderName(),
                        csrfToken.getToken()
                ));
    }

    @GetMapping("/providers")
    public ResponseEntity<AuthProvidersResponse> providers() {
        SocialLoginProviderCatalog providerCatalog =
                socialLoginProviderCatalogProvider.getIfAvailable();
        List<String> providers = providerCatalog == null
                ? List.of()
                : providerCatalog.availableProviderIds();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new AuthProvidersResponse(
                        providers,
                        authFeatureProperties.localRegistrationEnabled(),
                        authFeatureProperties.passwordResetEnabled(),
                        humanVerificationUseCase.siteKey().orElse(null)
                ));
    }

    @PostMapping("/local/registrations")
    public ResponseEntity<LocalRegistrationResponse> registerLocalAccount(
            @Valid @RequestBody LocalRegistrationRequest request,
            HttpServletRequest servletRequest
    ) {
        if (!authFeatureProperties.localRegistrationEnabled()) {
            throw new EmailVerificationDeliveryUnavailableException(
                    "자체 이메일 계정 등록이 비활성화됐습니다"
            );
        }
        authRateLimiter.checkRegistration(
                servletRequest.getRemoteAddr(),
                request.email()
        );
        humanVerificationUseCase.verify(new HumanVerificationCommand(
                request.turnstileToken(),
                servletRequest.getRemoteAddr(),
                Action.LOCAL_REGISTRATION
        ));
        try {
            registerLocalAccountUseCase.registerLocalAccount(new RegisterLocalAccountCommand(
                    request.email(),
                    request.displayName()
            ));
        } catch (IdentityConflictException ignored) {
            // 중복 신원을 뜻하는 충돌만 중립적으로 처리한다. 일시적인 영속화 실패는 별도 예외를
            // 사용하므로 503 응답 경계까지 전달해야 한다.
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(new LocalRegistrationResponse());
    }

    @PostMapping("/local/email-verifications")
    public ResponseEntity<Void> verifyLocalEmail(
            @Valid @RequestBody LocalEmailVerificationRequest request,
            HttpServletRequest servletRequest
    ) {
        authRateLimiter.checkVerification(
                servletRequest.getRemoteAddr(),
                request.token()
        );
        verifyLocalEmailUseCase.verifyLocalEmail(new VerifyLocalEmailCommand(
                request.token(),
                request.password()
        ));
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PostMapping("/local/password-reset-requests")
    public ResponseEntity<PasswordResetRequestResponse> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request, HttpServletRequest servletRequest
    ) {
        if (!authFeatureProperties.passwordResetEnabled()) {
            throw new EmailVerificationDeliveryUnavailableException("비밀번호 재설정 요청이 비활성화됐습니다");
        }
        authRateLimiter.checkRegistration(servletRequest.getRemoteAddr(), request.email());
        humanVerificationUseCase.verify(new HumanVerificationCommand(
                request.turnstileToken(),
                servletRequest.getRemoteAddr(),
                Action.PASSWORD_RESET_REQUEST
        ));
        passwordResetUseCase.requestPasswordReset(request.email());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(new PasswordResetRequestResponse(true));
    }

    @PostMapping("/local/password-resets")
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody PasswordResetCompletionRequest request, HttpServletRequest servletRequest
    ) {
        authRateLimiter.checkVerification(servletRequest.getRemoteAddr(), request.token());
        passwordResetUseCase.resetPassword(new ResetPasswordCommand(request.token(), request.password()));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

}
