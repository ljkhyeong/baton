package com.personal.baton.policy.workspace;

import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutinePhase;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("policy")
class SeasonCopyPolicyTest {

    @DisplayName("역할을 다음 시즌에 복사하면 정의만 이어가고 담당자와 배정 기간은 초기화한다")
    @Test
    void copiesRoleDefinitionWithoutAssignmentState() {
        UUID sourceRoleId = UUID.randomUUID();
        UUID targetSeasonId = UUID.randomUUID();
        Role source = Role.create(
                sourceRoleId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "진행자",
                "모임을 진행합니다",
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31),
                List.of("시간 확인", "질문 정리"),
                "발언 편중"
        );

        Role copied = source.copyToSeason(UUID.randomUUID(), targetSeasonId);

        assertThat(copied.getSeasonId()).isEqualTo(targetSeasonId);
        assertThat(copied.getPreviousRoleId()).isEqualTo(sourceRoleId);
        assertThat(copied.getName()).isEqualTo("진행자");
        assertThat(copied.getPurpose()).isEqualTo("모임을 진행합니다");
        assertThat(copied.getResponsibilities()).containsExactly("시간 확인", "질문 정리");
        assertThat(copied.getRisk()).isEqualTo("발언 편중");
        assertThat(copied.getCurrentMemberId()).isNull();
        assertThat(copied.getNextMemberId()).isNull();
        assertThat(copied.getAssignmentStartDate()).isNull();
        assertThat(copied.getAssignmentEndDate()).isNull();
    }

    @DisplayName("루틴을 다음 시즌에 복사하면 정의와 원본 계보를 유지하고 담당 역할만 새 역할로 바꾼다")
    @Test
    void copiesRoutineDefinitionWithMappedOwnerRole() {
        UUID sourceRoutineId = UUID.randomUUID();
        UUID targetSeasonId = UUID.randomUUID();
        UUID targetRoleId = UUID.randomUUID();
        Routine source = Routine.create(
                sourceRoutineId,
                UUID.randomUUID(),
                "질문 모으기",
                RoutinePhase.BEFORE,
                "모임 전날",
                UUID.randomUUID(),
                "질문을 한곳에 모읍니다",
                null,
                null
        );

        Routine copied = source.copyToSeason(UUID.randomUUID(), targetSeasonId, targetRoleId);

        assertThat(copied.getSeasonId()).isEqualTo(targetSeasonId);
        assertThat(copied.getPreviousRoutineId()).isEqualTo(sourceRoutineId);
        assertThat(copied.getOwnerRoleId()).isEqualTo(targetRoleId);
        assertThat(copied.getTitle()).isEqualTo(source.getTitle());
        assertThat(copied.getPhase()).isEqualTo(source.getPhase());
        assertThat(copied.getDueLabel()).isEqualTo(source.getDueLabel());
        assertThat(copied.getDetail()).isEqualTo(source.getDetail());
    }
}
