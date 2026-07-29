package com.personal.baton.policy.workspace;

import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Member;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("policy")
class MemberPolicyTest {

    @DisplayName("구성원 이름 변경은 공백을 정리하고 검증 실패 시 기존 이름을 보존한다")
    @Test
    void normalizesRenameAndPreservesExistingNameWhenValidationFails() {
        Member member = Member.create(UUID.randomUUID(), UUID.randomUUID(), "박민서");

        member.rename("  박민서(진행)  ");

        assertThat(member.getName()).isEqualTo("박민서(진행)");
        assertThatThrownBy(() -> member.rename("   "))
                .isInstanceOf(DomainValidationException.class);
        assertThat(member.getName()).isEqualTo("박민서(진행)");
    }

    @DisplayName("구성원을 반복 비활성화하면 최초 시각을 유지하고 복귀하면 활성 상태로 돌아간다")
    @Test
    void preservesFirstDeactivationTimeUntilReactivated() {
        Member member = Member.create(UUID.randomUUID(), UUID.randomUUID(), "박민서");
        Instant firstDeactivationTime = Instant.parse("2026-07-21T01:02:03Z");

        member.updateDeactivation(true, firstDeactivationTime);
        member.updateDeactivation(true, Instant.parse("2026-07-22T04:05:06Z"));

        assertThat(member.isActive()).isFalse();
        assertThat(member.getDeactivatedAt()).isEqualTo(firstDeactivationTime);

        member.updateDeactivation(false, null);

        assertThat(member.isActive()).isTrue();
        assertThat(member.getDeactivatedAt()).isNull();
    }
}
