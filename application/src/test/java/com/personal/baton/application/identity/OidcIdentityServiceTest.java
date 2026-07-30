package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.IdentityOperationException;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase.OidcAccountResult;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase.VerifiedOidcIdentity;
import com.personal.baton.application.identity.port.out.OidcIdentityRepository;
import com.personal.baton.application.identity.port.out.OidcIdentityRepository.ExternalIdentityResolution;
import com.personal.baton.domain.identity.OidcExternalIdentity;
import com.personal.baton.domain.identity.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OidcIdentityServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final String ISSUER = "https://login.example.com/oidc";
    private static final String SUBJECT = "subject-1001";

    @Mock
    private OidcIdentityRepository repository;

    private OidcIdentityService service;

    @BeforeEach
    void setUp() {
        service = new OidcIdentityService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @DisplayName("검증된 OIDC 외부 신원은 공급자 식별자 대신 내부 사용자 UUID로 해석한다")
    @Test
    void resolvesVerifiedExternalIdentityToInternalAccount() {
        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        ArgumentCaptor<OidcExternalIdentity> identityCaptor =
                ArgumentCaptor.forClass(OidcExternalIdentity.class);
        given(repository.resolveOrCreate(any(), any())).willAnswer(invocation -> {
            UserAccount account = invocation.getArgument(0);
            OidcExternalIdentity identity = invocation.getArgument(1);
            return new ExternalIdentityResolution(account, identity);
        });

        OidcAccountResult result = service.resolveAccount(
                new VerifiedOidcIdentity(ISSUER, SUBJECT)
        );

        verify(repository).resolveOrCreate(accountCaptor.capture(), identityCaptor.capture());
        assertThat(result.accountId()).isEqualTo(accountCaptor.getValue().getId());
        assertThat(result.createdAt()).isEqualTo(NOW);
        assertThat(identityCaptor.getValue().getUserAccountId()).isEqualTo(result.accountId());
        assertThat(identityCaptor.getValue().getIssuer()).isEqualTo(ISSUER);
        assertThat(identityCaptor.getValue().getSubject()).isEqualTo(SUBJECT);
        assertThat(identityCaptor.getValue().getIdentityKeyHash())
                .matches("[0-9a-f]{64}");
    }

    @DisplayName("같은 OIDC 외부 신원 해시는 재시작과 후보 UUID에 관계없이 동일하다")
    @Test
    void derivesStableExternalIdentityHash() {
        ArgumentCaptor<OidcExternalIdentity> identityCaptor =
                ArgumentCaptor.forClass(OidcExternalIdentity.class);
        given(repository.resolveOrCreate(any(), any())).willAnswer(invocation ->
                new ExternalIdentityResolution(
                        invocation.getArgument(0),
                        invocation.getArgument(1)
                )
        );

        service.resolveAccount(new VerifiedOidcIdentity(ISSUER, SUBJECT));
        service.resolveAccount(new VerifiedOidcIdentity(ISSUER, SUBJECT));

        verify(repository, org.mockito.Mockito.times(2))
                .resolveOrCreate(any(), identityCaptor.capture());
        assertThat(identityCaptor.getAllValues())
                .extracting(OidcExternalIdentity::getIdentityKeyHash)
                .containsOnly(identityCaptor.getAllValues().getFirst().getIdentityKeyHash());
        assertThat(identityCaptor.getAllValues())
                .extracting(OidcExternalIdentity::getUserAccountId)
                .doesNotHaveDuplicates();
    }

    @DisplayName("HTTPS URL이 아닌 issuer와 빈 subject는 외부 신원 저장 전에 거부한다")
    @Test
    void rejectsInvalidVerifiedIdentityShape() {
        assertThatThrownBy(() -> service.resolveAccount(
                new VerifiedOidcIdentity("http://login.example.com", SUBJECT)
        )).isInstanceOfSatisfying(
                IdentityOperationException.class,
                exception -> assertThat(exception.getCode())
                        .isEqualTo("INVALID_EXTERNAL_IDENTITY")
        );
        assertThatThrownBy(() -> service.resolveAccount(
                new VerifiedOidcIdentity(ISSUER, " ")
        )).isInstanceOfSatisfying(
                IdentityOperationException.class,
                exception -> assertThat(exception.getCode())
                        .isEqualTo("INVALID_EXTERNAL_IDENTITY")
        );

        verifyNoInteractions(repository);
    }

    @DisplayName("외부 신원 해시가 다른 원문 신원을 가리키면 계정 해석을 중단한다")
    @Test
    void rejectsExternalIdentityHashCollision() {
        UUID accountId = UUID.randomUUID();
        UserAccount account = UserAccount.create(accountId, NOW.minusSeconds(10));
        OidcExternalIdentity conflicting = OidcExternalIdentity.link(
                UUID.randomUUID(),
                "a".repeat(64),
                ISSUER,
                "different-subject",
                accountId,
                NOW.minusSeconds(10)
        );
        given(repository.resolveOrCreate(any(), any()))
                .willReturn(new ExternalIdentityResolution(account, conflicting));

        assertThatThrownBy(() -> service.resolveAccount(
                new VerifiedOidcIdentity(ISSUER, SUBJECT)
        )).isInstanceOfSatisfying(
                IdentityOperationException.class,
                exception -> assertThat(exception.getCode())
                        .isEqualTo("EXTERNAL_IDENTITY_CONFLICT")
        );
    }
}
