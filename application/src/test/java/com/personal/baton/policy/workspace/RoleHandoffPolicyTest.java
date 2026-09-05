package com.personal.baton.policy.workspace;

import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.domain.workspace.RoleHandoffTransitionException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("policy")
class RoleHandoffPolicyTest {

    private static final Instant PREPARED_AT = Instant.parse("2026-07-30T09:00:00Z");
    private static final UUID FROM_MEMBER_ID = UUID.randomUUID();
    private static final UUID TO_MEMBER_ID = UUID.randomUUID();

    @DisplayName("경고를 확인한 이전 담당자가 전달하면 다음 담당자만 인수인계를 수락할 수 있다")
    @Test
    void transfersAndAcceptsWithExplicitParticipants() {
        RoleHandoff handoff = preparedHandoff();

        assertThatThrownBy(() -> handoff.transfer(
                FROM_MEMBER_ID,
                PREPARED_AT.plusSeconds(60),
                2,
                1,
                0,
                false
        )).isInstanceOf(DomainValidationException.class);

        handoff.transfer(
                FROM_MEMBER_ID,
                PREPARED_AT.plusSeconds(60),
                2,
                1,
                0,
                true
        );

        assertThat(handoff.getStatus()).isEqualTo(RoleHandoffStatus.TRANSFERRED);
        assertThat(RoleHandoff.hasWarnings(
                handoff.getSnapshotItemCount(),
                handoff.getSnapshotIncompleteItemCount(),
                handoff.getSnapshotResourceCount()
        )).isTrue();
        assertThat(handoff.isWarningAcknowledged()).isTrue();
        assertThatThrownBy(() -> handoff.accept(
                FROM_MEMBER_ID,
                PREPARED_AT.plusSeconds(120)
        )).isInstanceOf(RoleHandoffTransitionException.class);

        handoff.accept(TO_MEMBER_ID, PREPARED_AT.plusSeconds(120));

        assertThat(handoff.getStatus()).isEqualTo(RoleHandoffStatus.ACCEPTED);
        assertThat(handoff.getAcceptedByMemberId()).isEqualTo(TO_MEMBER_ID);
    }

    @DisplayName("인수인계 수락은 역할 담당자와 다음 담당 기간을 한 번에 교체한다")
    @Test
    void acceptsRoleAssignmentAtomically() {
        UUID roleId = UUID.randomUUID();
        Role role = Role.create(
                roleId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "진행자",
                "모임을 진행한다",
                FROM_MEMBER_ID,
                null,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                List.of("모임 진행"),
                null
        );
        LocalDate incomingStartDate = LocalDate.of(2026, 8, 1);
        LocalDate incomingEndDate = LocalDate.of(2026, 8, 31);

        role.prepareHandoff(TO_MEMBER_ID);
        role.acceptHandoff(
                FROM_MEMBER_ID,
                TO_MEMBER_ID,
                incomingStartDate,
                incomingEndDate
        );

        assertThat(role.getCurrentMemberId()).isEqualTo(TO_MEMBER_ID);
        assertThat(role.getNextMemberId()).isNull();
        assertThat(role.getAssignmentStartDate()).isEqualTo(incomingStartDate);
        assertThat(role.getAssignmentEndDate()).isEqualTo(incomingEndDate);
    }

    @DisplayName("열린 역할 인수인계는 이전 담당자 명의로만 취소할 수 있다")
    @Test
    void cancelsOnlyWithPreviousMemberDeclaration() {
        RoleHandoff handoff = preparedHandoff();

        assertThatThrownBy(() -> handoff.cancel(
                TO_MEMBER_ID,
                PREPARED_AT.plusSeconds(60)
        )).isInstanceOf(RoleHandoffTransitionException.class);

        handoff.cancel(FROM_MEMBER_ID, PREPARED_AT.plusSeconds(60));

        assertThat(handoff.getStatus()).isEqualTo(RoleHandoffStatus.CANCELLED);
        assertThat(handoff.getCancelledByMemberId()).isEqualTo(FROM_MEMBER_ID);
    }

    private RoleHandoff preparedHandoff() {
        return RoleHandoff.prepare(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                FROM_MEMBER_ID,
                TO_MEMBER_ID,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                PREPARED_AT
        );
    }
}
