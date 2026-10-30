package com.personal.baton.adapter.out.persistence.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personal.baton.adapter.out.persistence.roundauth.AccountTeamMembershipJpaRepository;
import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
final class TeamAccessPersistenceAdapterTest {

    private final AccountTeamMembership membership = AccountTeamMembership.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.EPOCH
    );

    @Mock
    private AccountTeamMembershipJpaRepository memberships;

    @Mock
    private TeamInvitationJpaRepository invitations;

    @Mock
    private TeamAccessAuditJpaRepository audits;

    @InjectMocks
    private TeamAccessPersistenceAdapter adapter;

    @DisplayName("계정 연결 유일 제약 충돌은 저장 시점에 멤버십 충돌로 변환하고 원인을 보존한다")
    @ParameterizedTest
    @ValueSource(strings = {
            "baton.uk_account_team_memberships_account_team",
            "baton.uk_account_team_memberships_member"
    })
    void translatesMembershipUniqueConflict(String constraintName) {
        DataIntegrityViolationException cause = uniqueConstraintViolation(constraintName);
        when(memberships.saveAndFlush(membership)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveMembership(membership))
                .isInstanceOfSatisfying(
                        AccountMembershipConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("계정 연결과 무관한 제약 충돌은 그대로 전달한다")
    @Test
    void rethrowsUnknownConstraintViolation() {
        DataIntegrityViolationException cause = uniqueConstraintViolation("uk_unknown_constraint");
        when(memberships.saveAndFlush(membership)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveMembership(membership)).isSameAs(cause);
    }

    private DataIntegrityViolationException uniqueConstraintViolation(String constraintName) {
        ConstraintViolationException constraintViolation = mock(ConstraintViolationException.class);
        when(constraintViolation.getConstraintName()).thenReturn(constraintName);
        return new DataIntegrityViolationException("고유 제약 충돌", constraintViolation);
    }
}
