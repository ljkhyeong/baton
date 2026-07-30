package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.adapter.out.persistence.workspace.MemberJpaRepository;
import com.personal.baton.adapter.out.persistence.workspace.TeamJpaRepository;
import com.personal.baton.application.identity.error.MemberIdentityConflictException;
import com.personal.baton.domain.identity.MemberIdentityBinding;
import java.sql.SQLException;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityPersistenceAdapterTest {

    @Mock
    private UserAccountJpaRepository userAccountRepository;

    @Mock
    private MemberIdentityBindingJpaRepository bindingRepository;

    @Mock
    private MemberJpaRepository memberRepository;

    @Mock
    private TeamJpaRepository teamRepository;

    @InjectMocks
    private IdentityPersistenceAdapter adapter;

    @DisplayName("사용자 계정 잠금 실패 원인을 신원 결속 충돌 예외에 보존한다")
    @Test
    void preservesAccountLockFailureAsIdentityConflict() {
        UUID accountId = UUID.randomUUID();
        PessimisticLockingFailureException cause =
                new PessimisticLockingFailureException("계정 잠금 시간 초과");
        when(userAccountRepository.findByIdForUpdate(accountId)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.findUserAccountByIdForUpdate(accountId))
                .isInstanceOfSatisfying(
                        MemberIdentityConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("구성원 잠금 실패 원인을 신원 결속 충돌 예외에 보존한다")
    @Test
    void preservesMemberLockFailureAsIdentityConflict() {
        UUID teamId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        PessimisticLockingFailureException cause =
                new PessimisticLockingFailureException("구성원 잠금 시간 초과");
        when(memberRepository.findByTeamIdAndIdForUpdate(teamId, memberId)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.findMemberByTeamIdAndIdForUpdate(teamId, memberId))
                .isInstanceOfSatisfying(
                        MemberIdentityConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("팀별 계정 결속 유일성 충돌 원인을 신원 결속 충돌 예외에 보존한다")
    @Test
    void translatesBindingUniquenessViolation() {
        MemberIdentityBinding binding = mock(MemberIdentityBinding.class);
        DataIntegrityViolationException cause =
                uniqueConstraintViolation("uk_member_identity_bindings_team_account");
        when(bindingRepository.saveAndFlush(binding)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveBinding(binding))
                .isInstanceOfSatisfying(
                        MemberIdentityConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("알 수 없는 신원 결속 데이터 제약 위반은 원래 예외를 그대로 전달한다")
    @Test
    void preservesUnknownDataIntegrityViolation() {
        MemberIdentityBinding binding = mock(MemberIdentityBinding.class);
        DataIntegrityViolationException cause =
                uniqueConstraintViolation("unexpected_constraint");
        when(bindingRepository.saveAndFlush(binding)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveBinding(binding)).isSameAs(cause);
    }

    private DataIntegrityViolationException uniqueConstraintViolation(String constraintName) {
        ConstraintViolationException hibernateCause = new ConstraintViolationException(
                "constraint violation",
                new SQLException("duplicate"),
                constraintName
        );
        return new DataIntegrityViolationException("constraint violation", hibernateCause);
    }
}
