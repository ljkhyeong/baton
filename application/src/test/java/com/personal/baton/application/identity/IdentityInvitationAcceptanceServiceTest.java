package com.personal.baton.application.identity;

import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.PreviewedOwnerBootstrapInvitation;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityInvitationAcceptanceServiceTest {

    @Mock
    private OwnerBootstrapInvitationUseCase bootstrapInvitations;

    @Mock
    private MemberInvitationUseCase memberInvitations;

    @DisplayName("mi1_로 시작하는 43자 bootstrap 토큰은 일반 초대로 오분류하지 않는다")
    @Test
    void routesPrefixCollisionWithBootstrapTokenByExactShape() {
        String bootstrapToken = "mi1_" + "A".repeat(39);
        AuthenticatedAccount account = new AuthenticatedAccount(UUID.randomUUID());
        PreviewedOwnerBootstrapInvitation bootstrapPreview =
                new PreviewedOwnerBootstrapInvitation(
                        UUID.randomUUID(),
                        "BATON 팀",
                        UUID.randomUUID(),
                        "OWNER",
                        Instant.parse("2026-07-30T13:00:00Z"),
                        false
                );
        when(bootstrapInvitations.preview(bootstrapToken, account))
                .thenReturn(bootstrapPreview);
        IdentityInvitationAcceptanceService service =
                new IdentityInvitationAcceptanceService(
                        bootstrapInvitations,
                        memberInvitations
                );

        var preview = service.preview(bootstrapToken, account);

        assertThat(preview.teamId()).isEqualTo(bootstrapPreview.teamId());
        verify(bootstrapInvitations).preview(bootstrapToken, account);
        verifyNoInteractions(memberInvitations);
    }
}
