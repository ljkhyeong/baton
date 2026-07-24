package com.personal.baton.policy.workspace;

import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.DomainValidationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("policy")
class DecisionPolicyTest {

    @DisplayName("결정 수정 검증에 실패하면 기존 내용과 관련 역할을 그대로 보존한다")
    @Test
    void preservesExistingValuesWhenUpdateValidationFails() {
        UUID originalAuthorId = UUID.randomUUID();
        UUID originalRoleId = UUID.randomUUID();
        Decision decision = Decision.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "질문은 전날 마감한다",
                "준비 시간을 확보합니다",
                "당일에도 받는 방안을 검토했습니다",
                Instant.parse("2026-07-20T03:04:05Z"),
                originalAuthorId,
                List.of(originalRoleId)
        );
        UUID duplicatedRoleId = UUID.randomUUID();

        assertThatThrownBy(() -> decision.update(
                "바뀐 결정",
                "바뀐 이유",
                "바뀐 대안",
                UUID.randomUUID(),
                List.of(duplicatedRoleId, duplicatedRoleId)
        )).isInstanceOf(DomainValidationException.class);

        assertThat(decision.getTitle()).isEqualTo("질문은 전날 마감한다");
        assertThat(decision.getReason()).isEqualTo("준비 시간을 확보합니다");
        assertThat(decision.getAlternative()).isEqualTo("당일에도 받는 방안을 검토했습니다");
        assertThat(decision.getAuthorMemberId()).isEqualTo(originalAuthorId);
        assertThat(decision.getRoleIds()).containsExactly(originalRoleId);
    }

    @DisplayName("결정을 반복 보관하면 최초 보관 시각을 유지하고 복원하면 시각을 지운다")
    @Test
    void preservesFirstArchiveTimeUntilRestored() {
        UUID roleId = UUID.randomUUID();
        Decision decision = Decision.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "질문은 전날 마감한다",
                "준비 시간을 확보합니다",
                "",
                Instant.parse("2026-07-20T03:04:05Z"),
                UUID.randomUUID(),
                List.of(roleId)
        );
        Instant firstArchiveTime = Instant.parse("2026-07-21T01:02:03Z");

        decision.updateArchive(true, firstArchiveTime);
        decision.updateArchive(true, Instant.parse("2026-07-22T04:05:06Z"));

        assertThat(decision.getArchivedAt()).isEqualTo(firstArchiveTime);
        assertThatThrownBy(() -> decision.update(
                "보관된 결정 수정",
                "복원 전에는 바꿀 수 없습니다",
                "",
                UUID.randomUUID(),
                List.of(roleId)
        )).isInstanceOf(DomainValidationException.class);

        decision.updateArchive(false, Instant.parse("2026-07-23T07:08:09Z"));

        assertThat(decision.getArchivedAt()).isNull();
    }
}
