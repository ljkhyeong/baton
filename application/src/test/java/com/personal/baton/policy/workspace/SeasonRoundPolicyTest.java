package com.personal.baton.policy.workspace;

import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("policy")
class SeasonRoundPolicyTest {

    @DisplayName("회차 수정 검증에 실패하면 기존 이름과 모임 날짜를 그대로 보존한다")
    @Test
    void preservesExistingValuesWhenUpdateValidationFails() {
        SeasonRound round = SeasonRound.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "1회차",
                LocalDate.of(2026, 7, 28)
        );

        assertThatNullPointerException()
                .isThrownBy(() -> round.update("첫 모임", null, ZoneId.of("Asia/Seoul")));

        assertThat(round.getName()).isEqualTo("1회차");
        assertThat(round.getMeetingDate()).isEqualTo(LocalDate.of(2026, 7, 28));
    }

    @DisplayName("회차를 반복 보관하면 최초 시각을 유지하고 복원 뒤 다시 수정할 수 있다")
    @Test
    void preservesFirstArchiveTimeUntilRestored() {
        SeasonRound round = SeasonRound.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "1회차",
                LocalDate.of(2026, 7, 28)
        );
        Instant firstArchiveTime = Instant.parse("2026-07-21T01:02:03Z");

        round.updateArchive(true, firstArchiveTime);
        round.updateArchive(true, Instant.parse("2026-07-22T04:05:06Z"));

        assertThat(round.getArchivedAt()).isEqualTo(firstArchiveTime);
        assertThatThrownBy(() -> round.update("보관 중 수정", LocalDate.of(2026, 7, 29), ZoneId.of("Asia/Seoul")))
                .isInstanceOf(DomainValidationException.class);

        round.updateArchive(false, Instant.parse("2026-07-23T07:08:09Z"));
        round.update("첫 모임", LocalDate.of(2026, 7, 29), ZoneId.of("Asia/Seoul"));

        assertThat(round.getArchivedAt()).isNull();
        assertThat(round.getName()).isEqualTo("첫 모임");
        assertThat(round.getMeetingDate()).isEqualTo(LocalDate.of(2026, 7, 29));
    }
}
