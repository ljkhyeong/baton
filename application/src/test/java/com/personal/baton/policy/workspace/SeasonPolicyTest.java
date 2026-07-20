package com.personal.baton.policy.workspace;

import com.personal.baton.domain.workspace.Season;
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
}
