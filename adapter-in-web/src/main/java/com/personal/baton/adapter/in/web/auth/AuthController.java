package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.auth.AuthRequests.LocalEmailVerificationRequest;
import com.personal.baton.adapter.in.web.auth.AuthRequests.LocalRegistrationRequest;
import com.personal.baton.adapter.in.web.auth.AuthResponses.AuthenticatedSessionResponse;
import com.personal.baton.adapter.in.web.auth.AuthResponses.AuthProvidersResponse;
import com.personal.baton.adapter.in.web.auth.AuthResponses.CsrfResponse;
import com.personal.baton.adapter.in.web.auth.AuthResponses.LocalRegistrationResponse;
import com.personal.baton.adapter.in.web.auth.AuthResponses.UnauthenticatedSessionResponse;
import com.personal.baton.adapter.in.web.config.AuthFeatureProperties;
import com.personal.baton.application.identity.error.EmailVerificationDeliveryUnavailableException;
import com.personal.baton.application.identity.error.IdentityConflictException;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
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

    private final RegisterLocalAccountUseCase registerLocalAccountUseCase;
    private final VerifyLocalEmailUseCase verifyLocalEmailUseCase;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider;
    private final AuthRateLimiter authRateLimiter;
    private final AuthFeatureProperties authFeatureProperties;

    public AuthController(
            RegisterLocalAccountUseCase registerLocalAccountUseCase,
            VerifyLocalEmailUseCase verifyLocalEmailUseCase,
            ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider,
            AuthRateLimiter authRateLimiter,
            AuthFeatureProperties authFeatureProperties
    ) {
        this.registerLocalAccountUseCase = registerLocalAccountUseCase;
        this.verifyLocalEmailUseCase = verifyLocalEmailUseCase;
        this.clientRegistrationRepositoryProvider = clientRegistrationRepositoryProvider;
        this.authRateLimiter = authRateLimiter;
        this.authFeatureProperties = authFeatureProperties;
    }

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(HttpServletRequest request) {
        CsrfToken csrfToken = csrfToken(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(
                        csrfToken.getHeaderName(),
                        csrfToken.getToken()
                ));
    }

    @GetMapping("/session")
    public ResponseEntity<Object> session(
            Authentication authentication,
            HttpServletRequest request
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal()
                instanceof AuthenticatedAccountPrincipal principal)) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new UnauthenticatedSessionResponse());
        }
        CsrfToken csrfToken = csrfToken(request);
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
        ClientRegistrationRepository registrations =
                clientRegistrationRepositoryProvider.getIfAvailable();
        List<String> providers = List.of("google", "naver").stream()
                .filter(provider -> registrations != null
                        && registrations.findByRegistrationId(provider) != null)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new AuthProvidersResponse(
                        providers,
                        authFeatureProperties.localRegistrationEnabled()
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
        authRateLimiter.checkRegistration(servletRequest.getRemoteAddr(), request.email());
        try {
            registerLocalAccountUseCase.registerLocalAccount(new RegisterLocalAccountCommand(
                    request.email(),
                    request.displayName()
            ));
        } catch (IdentityConflictException ignored) {
            // Only semantic duplicate identity conflicts are neutralized. Transient
            // persistence failures use a separate exception and must reach the 503 boundary.
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
        authRateLimiter.checkVerification(servletRequest.getRemoteAddr(), request.token());
        verifyLocalEmailUseCase.verifyLocalEmail(new VerifyLocalEmailCommand(
                request.token(),
                request.password()
        ));
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private CsrfToken csrfToken(HttpServletRequest request) {
        Object attribute = request.getAttribute(CsrfToken.class.getName());
        if (attribute instanceof CsrfToken csrfToken) {
            return csrfToken;
        }
        throw new IllegalStateException("CSRF token을 준비하지 못했습니다");
    }

}
