package com.personal.baton.policy.workspace;

import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.HandoffItem;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("policy")
class HandoffItemPolicyTest {

    @DisplayName("인수인계 수정 검증에 실패하면 기존 내용과 완료 여부를 그대로 보존한다")
    @Test
    void preservesExistingValuesWhenUpdateValidationFails() {
        UUID originalRoleId = UUID.randomUUID();
        HandoffItem item = HandoffItem.create(
                UUID.randomUUID(),
                originalRoleId,
                "질문 문서 권한 넘기기",
                HandoffCategory.RESOURCE,
                true,
                Instant.parse("2026-07-20T01:02:03Z")
        );

        assertThatThrownBy(() -> item.update(
                UUID.randomUUID(),
                " ",
                HandoffCategory.ADVICE
        )).isInstanceOf(DomainValidationException.class);

        assertThat(item.getRoleId()).isEqualTo(originalRoleId);
        assertThat(item.getLabel()).isEqualTo("질문 문서 권한 넘기기");
        assertThat(item.getCategory()).isEqualTo(HandoffCategory.RESOURCE);
        assertThat(item.isCompleted()).isTrue();
    }

    @DisplayName("인수인계를 반복 보관하면 최초 보관 시각을 유지하고 복원하면 완료 상태를 보존한다")
    @Test
    void preservesFirstArchiveTimeAndCompletionUntilRestored() {
        HandoffItem item = HandoffItem.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "질문 문서 권한 넘기기",
                HandoffCategory.RESOURCE,
                true,
                Instant.parse("2026-07-20T01:02:03Z")
        );
        Instant firstArchiveTime = Instant.parse("2026-07-21T01:02:03Z");

        item.updateArchive(true, firstArchiveTime);
        item.updateArchive(true, Instant.parse("2026-07-22T04:05:06Z"));

        assertThat(item.getArchivedAt()).isEqualTo(firstArchiveTime);
        assertThatThrownBy(() -> item.update(
                UUID.randomUUID(),
                "보관된 인수인계 수정",
                HandoffCategory.ADVICE
        )).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> item.updateCompletion(false))
                .isInstanceOf(DomainValidationException.class);

        item.updateArchive(false, Instant.parse("2026-07-23T07:08:09Z"));

        assertThat(item.getArchivedAt()).isNull();
        assertThat(item.isCompleted()).isTrue();
    }
}
