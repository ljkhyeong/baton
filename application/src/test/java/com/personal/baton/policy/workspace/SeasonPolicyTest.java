package com.personal.baton.policy.workspace;

import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Season;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @DisplayName("시즌 날짜는 1001년부터 9998년까지 허용한다")
    @Test
    void acceptsSupportedYearBoundaries() {
        Season season = Season.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "긴 시즌",
                LocalDate.of(1001, 1, 1),
                LocalDate.of(9998, 12, 31)
        );

        assertThat(season.getStartDate()).isEqualTo(LocalDate.of(1001, 1, 1));
        assertThat(season.getEndDate()).isEqualTo(LocalDate.of(9998, 12, 31));
    }

    @DisplayName("지원 연도를 벗어난 시즌 시작일과 종료일은 입력 오류다")
    @ParameterizedTest
    @CsvSource({"1000-12-31,2026-07-20", "2026-07-20,9999-01-01", "2026-07-20,+10000-01-01"})
    void rejectsYearsOutsideSupportedRange(LocalDate startDate, LocalDate endDate) {
        assertThatThrownBy(() -> Season.validatePeriod(startDate, endDate))
                .isInstanceOf(DomainValidationException.class);
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

}
