package com.personal.baton.application.workspace;

import com.personal.baton.domain.workspace.ResourceReviewSchedule;
import com.personal.baton.domain.workspace.DomainValidationException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("policy")
class ResourceReviewSchedulePolicyTest {
    @Test @DisplayName("자료 확인일 당일부터 재확인이 필요하며 확인하면 달력 날짜로 주기를 갱신한다")
    void appliesCalendarDateBoundary() {
        var schedule = ResourceReviewSchedule.create(UUID.randomUUID());
        var due = LocalDate.of(2026, 9, 5);
        schedule.configure(30, due);
        assertThat(schedule.isDueOn(due.minusDays(1))).isFalse();
        assertThat(schedule.isDueOn(due)).isTrue();
        assertThat(schedule.isDueOn(due.plusDays(1))).isTrue();
        schedule.confirmOn(due.plusDays(1));
        assertThat(schedule.getNextReviewOn()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThatThrownBy(() -> schedule.configure(0, due)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> schedule.configure(null, due)).isInstanceOf(DomainValidationException.class);
        schedule.configure(null, null);
        schedule.confirmOn(due);
        assertThat(schedule.getNextReviewOn()).isNull();
    }
}
