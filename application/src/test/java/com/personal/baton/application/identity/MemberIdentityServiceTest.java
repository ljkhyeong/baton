package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.IdentityNotFoundException;
import com.personal.baton.application.identity.error.InactiveMemberIdentityException;
import com.personal.baton.application.identity.error.MemberIdentityConflictException;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.MemberIdentityResult;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.domain.identity.MemberIdentityBinding;
import com.personal.baton.domain.identity.MemberIdentityRole;
import com.personal.baton.domain.identity.UserAccount;
import com.personal.baton.domain.workspace.Member;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MemberIdentityServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final UUID TEAM_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEMBER_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OTHER_MEMBER_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID OTHER_ACCOUNT_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Mock
    private IdentityRepository repository;

    private MemberIdentityService service;

    @BeforeEach
    void setUp() {
        service = new MemberIdentityService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @DisplayName("인증 계정과 활동 중인 팀 구성원을 잠근 뒤 불변 결속을 만든다")
    @Test
    void bindsAuthenticatedAccountToActiveMember() {
        UserAccount account = UserAccount.create(ACCOUNT_ID, NOW.minusSeconds(60));
        Member member = Member.create(MEMBER_ID, TEAM_ID, "박민서");
        given(repository.findUserAccountByIdForUpdate(ACCOUNT_ID))
                .willReturn(Optional.of(account));
        given(repository.findMemberByTeamIdAndIdForUpdate(TEAM_ID, MEMBER_ID))
                .willReturn(Optional.of(member));
        given(repository.findBindingByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(repository.findBindingByTeamIdAndUserAccountId(TEAM_ID, ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(repository.saveBinding(any(MemberIdentityBinding.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MemberIdentityResult result = service.bindMember(
                TEAM_ID,
                MEMBER_ID,
                new AuthenticatedAccount(ACCOUNT_ID)
        );

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.teamId()).isEqualTo(TEAM_ID);
        assertThat(result.memberId()).isEqualTo(MEMBER_ID);
        assertThat(result.boundAt()).isEqualTo(NOW);
        InOrder order = inOrder(repository);
        order.verify(repository).findUserAccountByIdForUpdate(ACCOUNT_ID);
        order.verify(repository).findMemberByTeamIdAndIdForUpdate(TEAM_ID, MEMBER_ID);
        order.verify(repository).findBindingByMemberId(MEMBER_ID);
        order.verify(repository).findBindingByTeamIdAndUserAccountId(TEAM_ID, ACCOUNT_ID);
        order.verify(repository).saveBinding(any(MemberIdentityBinding.class));
    }

    @DisplayName("신규 워크스페이스의 선택한 구성원은 인증 계정의 유일한 OWNER로 결속한다")
    @Test
    void bindsInitialWorkspaceOwner() {
        given(repository.findUserAccountByIdForUpdate(ACCOUNT_ID))
                .willReturn(Optional.of(UserAccount.create(ACCOUNT_ID, NOW.minusSeconds(60))));
        given(repository.findMemberByTeamIdAndIdForUpdate(TEAM_ID, MEMBER_ID))
                .willReturn(Optional.of(Member.create(MEMBER_ID, TEAM_ID, "박민서")));
        given(repository.findBindingByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(repository.findBindingByTeamIdAndUserAccountId(TEAM_ID, ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(repository.findOwnerBindingByTeamId(TEAM_ID)).willReturn(Optional.empty());
        given(repository.saveBinding(any(MemberIdentityBinding.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MemberIdentityResult result = service.bindInitialOwner(
                TEAM_ID,
                MEMBER_ID,
                new AuthenticatedAccount(ACCOUNT_ID)
        );

        assertThat(result.role()).isEqualTo(MemberIdentityRole.OWNER);
        assertThat(result.boundAt()).isEqualTo(NOW);
        verify(repository).saveBinding(any(MemberIdentityBinding.class));
    }

    @DisplayName("같은 계정과 구성원의 OWNER 결속 재생은 새 행을 만들지 않는다")
    @Test
    void replaysInitialWorkspaceOwnerBinding() {
        MemberIdentityBinding existing = MemberIdentityBinding.bind(
                MEMBER_ID,
                TEAM_ID,
                ACCOUNT_ID,
                NOW.minusSeconds(30),
                MemberIdentityRole.OWNER
        );
        given(repository.findUserAccountByIdForUpdate(ACCOUNT_ID))
                .willReturn(Optional.of(UserAccount.create(ACCOUNT_ID, NOW.minusSeconds(60))));
        given(repository.findMemberByTeamIdAndIdForUpdate(TEAM_ID, MEMBER_ID))
                .willReturn(Optional.of(Member.create(MEMBER_ID, TEAM_ID, "박민서")));
        given(repository.findBindingByMemberId(MEMBER_ID)).willReturn(Optional.of(existing));

        MemberIdentityResult result = service.bindInitialOwner(
                TEAM_ID,
                MEMBER_ID,
                new AuthenticatedAccount(ACCOUNT_ID)
        );

        assertThat(result.role()).isEqualTo(MemberIdentityRole.OWNER);
        assertThat(result.boundAt()).isEqualTo(NOW.minusSeconds(30));
        verify(repository, never()).saveBinding(any());
    }

    @DisplayName("신규 OWNER 결속은 기존 구성원 결속이나 팀 OWNER가 있으면 거부한다")
    @Test
    void rejectsConflictingInitialWorkspaceOwnerBinding() {
        given(repository.findUserAccountByIdForUpdate(ACCOUNT_ID))
                .willReturn(Optional.of(UserAccount.create(ACCOUNT_ID, NOW.minusSeconds(60))));
        given(repository.findMemberByTeamIdAndIdForUpdate(TEAM_ID, MEMBER_ID))
                .willReturn(Optional.of(Member.create(MEMBER_ID, TEAM_ID, "박민서")));
        given(repository.findBindingByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(repository.findBindingByTeamIdAndUserAccountId(TEAM_ID, ACCOUNT_ID))
                .willReturn(Optional.empty());
        given(repository.findOwnerBindingByTeamId(TEAM_ID))
                .willReturn(Optional.of(MemberIdentityBinding.bind(
                        OTHER_MEMBER_ID,
                        TEAM_ID,
                        OTHER_ACCOUNT_ID,
                        NOW.minusSeconds(30),
                        MemberIdentityRole.OWNER
                )));

        assertThatThrownBy(() -> service.bindInitialOwner(
                TEAM_ID,
                MEMBER_ID,
                new AuthenticatedAccount(ACCOUNT_ID)
        )).isInstanceOf(MemberIdentityConflictException.class);

        verify(repository, never()).saveBinding(any());
    }

    @DisplayName("결속 뒤 구성원이 활동 종료해도 같은 재시도는 새 행 없이 최초 결과를 반환한다")
    @Test
    void replaysSameBindingWithoutSavingAgain() {
        MemberIdentityBinding existing = binding(MEMBER_ID, ACCOUNT_ID);
        Member member = Member.create(MEMBER_ID, TEAM_ID, "박민서");
        member.updateDeactivation(true, NOW.minusSeconds(10));
        given(repository.findUserAccountByIdForUpdate(ACCOUNT_ID))
                .willReturn(Optional.of(UserAccount.create(ACCOUNT_ID, NOW.minusSeconds(60))));
        given(repository.findMemberByTeamIdAndIdForUpdate(TEAM_ID, MEMBER_ID))
                .willReturn(Optional.of(member));
        given(repository.findBindingByMemberId(MEMBER_ID)).willReturn(Optional.of(existing));

        MemberIdentityResult result = service.bindMember(
                TEAM_ID,
                MEMBER_ID,
                new AuthenticatedAccount(ACCOUNT_ID)
        );

        assertThat(result.boundAt()).isEqualTo(NOW.minusSeconds(30));
        verify(repository, never()).saveBinding(any());
    }

    @DisplayName("다른 구성원에 이미 연결된 팀 사용자 계정은 두 번째 결속을 거부한다")
    @Test
    void rejectsAccountAlreadyBoundToAnotherMemberInTeam() {
        given(repository.findUserAccountByIdForUpdate(ACCOUNT_ID))
                .willReturn(Optional.of(UserAccount.create(ACCOUNT_ID, NOW.minusSeconds(60))));
        given(repository.findMemberByTeamIdAndIdForUpdate(TEAM_ID, MEMBER_ID))
                .willReturn(Optional.of(Member.create(MEMBER_ID, TEAM_ID, "박민서")));
        given(repository.findBindingByMemberId(MEMBER_ID)).willReturn(Optional.empty());
        given(repository.findBindingByTeamIdAndUserAccountId(TEAM_ID, ACCOUNT_ID))
                .willReturn(Optional.of(binding(OTHER_MEMBER_ID, ACCOUNT_ID)));

        assertThatThrownBy(() -> service.bindMember(
                TEAM_ID,
                MEMBER_ID,
                new AuthenticatedAccount(ACCOUNT_ID)
        )).isInstanceOf(MemberIdentityConflictException.class);

        verify(repository, never()).saveBinding(any());
    }

    @DisplayName("다른 사용자 계정에 이미 연결된 구성원은 재연결을 거부한다")
    @Test
    void rejectsMemberAlreadyBoundToAnotherAccount() {
        given(repository.findUserAccountByIdForUpdate(ACCOUNT_ID))
                .willReturn(Optional.of(UserAccount.create(ACCOUNT_ID, NOW.minusSeconds(60))));
        given(repository.findMemberByTeamIdAndIdForUpdate(TEAM_ID, MEMBER_ID))
                .willReturn(Optional.of(Member.create(MEMBER_ID, TEAM_ID, "박민서")));
        given(repository.findBindingByMemberId(MEMBER_ID))
                .willReturn(Optional.of(binding(MEMBER_ID, OTHER_ACCOUNT_ID)));

        assertThatThrownBy(() -> service.bindMember(
                TEAM_ID,
                MEMBER_ID,
                new AuthenticatedAccount(ACCOUNT_ID)
        )).isInstanceOf(MemberIdentityConflictException.class);

        verify(repository, never()).saveBinding(any());
    }

    @DisplayName("활동 종료한 구성원은 인증 계정과 새로 연결할 수 없다")
    @Test
    void rejectsInactiveMember() {
        Member member = Member.create(MEMBER_ID, TEAM_ID, "박민서");
        member.updateDeactivation(true, NOW.minusSeconds(10));
        given(repository.findUserAccountByIdForUpdate(ACCOUNT_ID))
                .willReturn(Optional.of(UserAccount.create(ACCOUNT_ID, NOW.minusSeconds(60))));
        given(repository.findMemberByTeamIdAndIdForUpdate(TEAM_ID, MEMBER_ID))
                .willReturn(Optional.of(member));

        assertThatThrownBy(() -> service.bindMember(
                TEAM_ID,
                MEMBER_ID,
                new AuthenticatedAccount(ACCOUNT_ID)
        )).isInstanceOf(InactiveMemberIdentityException.class);

        verify(repository, never()).saveBinding(any());
    }

    @DisplayName("인증 계정이나 팀 구성원이 없으면 대상 식별자를 노출하지 않는 찾기 오류로 거부한다")
    @Test
    void rejectsMissingAccountOrMember() {
        given(repository.findUserAccountByIdForUpdate(ACCOUNT_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.bindMember(
                TEAM_ID,
                MEMBER_ID,
                new AuthenticatedAccount(ACCOUNT_ID)
        )).isInstanceOfSatisfying(
                IdentityNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ACCOUNT_NOT_FOUND")
        );

        given(repository.findUserAccountByIdForUpdate(ACCOUNT_ID))
                .willReturn(Optional.of(UserAccount.create(ACCOUNT_ID, NOW.minusSeconds(60))));
        given(repository.findMemberByTeamIdAndIdForUpdate(TEAM_ID, MEMBER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.bindMember(
                TEAM_ID,
                MEMBER_ID,
                new AuthenticatedAccount(ACCOUNT_ID)
        )).isInstanceOfSatisfying(
                IdentityNotFoundException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("MEMBER_NOT_FOUND")
        );
    }

    @DisplayName("ROUND 권한 조회는 계정과 연결된 활동 중인 팀 구성원만 반환한다")
    @Test
    void findsOnlyActiveMemberForAuthenticatedAccount() {
        MemberIdentityBinding binding = binding(MEMBER_ID, ACCOUNT_ID);
        Member activeMember = Member.create(MEMBER_ID, TEAM_ID, "박민서");
        given(repository.findBindingByTeamIdAndUserAccountId(TEAM_ID, ACCOUNT_ID))
                .willReturn(Optional.of(binding));
        given(repository.findMemberByTeamIdAndId(TEAM_ID, MEMBER_ID))
                .willReturn(Optional.of(activeMember));

        Optional<MemberIdentityResult> active = service.findActiveMember(
                TEAM_ID,
                new AuthenticatedAccount(ACCOUNT_ID)
        );

        assertThat(active).isPresent();

        activeMember.updateDeactivation(true, NOW);

        Optional<MemberIdentityResult> inactive = service.findActiveMember(
                TEAM_ID,
                new AuthenticatedAccount(ACCOUNT_ID)
        );

        assertThat(inactive).isEmpty();
    }

    @DisplayName("워크스페이스 변경 권한 조회는 결속 구성원 행을 공유 잠금해 활동 종료와의 경쟁을 막는다")
    @Test
    void locksBoundMemberForWorkspaceMutationAuthorization() {
        MemberIdentityBinding binding = binding(MEMBER_ID, ACCOUNT_ID);
        Member activeMember = Member.create(MEMBER_ID, TEAM_ID, "박민서");
        given(repository.findBindingByTeamIdAndUserAccountId(TEAM_ID, ACCOUNT_ID))
                .willReturn(Optional.of(binding));
        given(repository.findMemberByTeamIdAndIdWithSharedLock(TEAM_ID, MEMBER_ID))
                .willReturn(Optional.of(activeMember));

        Optional<MemberIdentityResult> result = service.findActiveMemberForMutation(
                TEAM_ID,
                new AuthenticatedAccount(ACCOUNT_ID)
        );

        assertThat(result).isPresent();
        verify(repository).findMemberByTeamIdAndIdWithSharedLock(TEAM_ID, MEMBER_ID);
        verify(repository, never()).findMemberByTeamIdAndId(TEAM_ID, MEMBER_ID);
    }

    private MemberIdentityBinding binding(UUID memberId, UUID accountId) {
        return MemberIdentityBinding.bind(
                memberId,
                TEAM_ID,
                accountId,
                NOW.minusSeconds(30)
        );
    }
}
