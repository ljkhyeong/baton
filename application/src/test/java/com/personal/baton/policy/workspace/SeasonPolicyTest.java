package com.personal.baton.policy.workspace;

import com.personal.baton.domain.workspace.Season;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@Tag("policy")
class SeasonPolicyTest {

    @DisplayName("시즌 시작일과 종료일이 같으면 시즌을 만들 수 있다")
    @Test
    void allowsSameStartAndEndDate() {
        LocalDate date = LocalDate.of(2026, 7, 20);

        Season season = Season.create(UUID.randomUUID(), UUID.randomUUID(), "하루 시즌", date, date);

        assertThat(season.getStartDate()).isEqualTo(date);
        assertThat(season.getEndDate()).isEqualTo(date);
    }

    @DisplayName("시즌 시작일이 종료일보다 늦으면 시즌을 만들 수 없다")
    @Test
    void rejectsStartDateAfterEndDate() {
        assertThatIllegalArgumentException().isThrownBy(() -> Season.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "잘못된 시즌",
                LocalDate.of(2026, 7, 21),
                LocalDate.of(2026, 7, 20)
        ));
    }

    @DisplayName("시즌을 반복 종료하면 최초 종료 시각을 유지하고 다시 열면 종료 시각을 지운다")
    @Test
    void preservesFirstEndingTimeUntilReopened() {
        Season season = Season.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "여름 시즌",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31)
        );
        Instant firstEndingTime = Instant.parse("2026-09-01T00:00:00Z");

        season.updateEnding(true, firstEndingTime);
        season.updateEnding(true, Instant.parse("2026-09-02T00:00:00Z"));

        assertThat(season.getEndedAt()).isEqualTo(firstEndingTime);
        assertThat(season.isEnded()).isTrue();

        season.updateEnding(false, Instant.parse("2026-09-03T00:00:00Z"));

        assertThat(season.getEndedAt()).isNull();
        assertThat(season.isEnded()).isFalse();
    }

    @DisplayName("후속 시즌은 이전 시즌 식별자를 계보로 보존한다")
    @Test
    void preservesPreviousSeasonLineage() {
        UUID previousSeasonId = UUID.randomUUID();

        Season successor = Season.createSuccessor(
                UUID.randomUUID(),
                UUID.randomUUID(),
                previousSeasonId,
                "가을 시즌",
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 31)
        );

        assertThat(successor.getPreviousSeasonId()).isEqualTo(previousSeasonId);
    }
}
