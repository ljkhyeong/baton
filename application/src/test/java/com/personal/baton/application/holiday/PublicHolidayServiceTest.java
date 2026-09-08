package com.personal.baton.application.holiday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.personal.baton.application.holiday.port.out.PublicHolidayClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("policy")
class PublicHolidayServiceTest {
    @Test
    @DisplayName("한국 시각의 새해부터 조회 범위를 바꾸고 범위 밖 요청은 외부 API를 호출하지 않는다")
    void boundsQueriesByKoreanYear() {
        var client = mock(PublicHolidayClient.class);
        var service = new PublicHolidayService(client,
                Clock.fixed(Instant.parse("2026-12-31T15:00:00Z"), ZoneOffset.UTC));
        for (int year : new int[] {2026, 2027, 2028}) {
            when(client.find(year)).thenReturn(PublicHolidayCalendar.empty(year, PublicHolidayCalendar.Status.DISABLED));
            assertThat(service.getHolidays(year).status()).isEqualTo(PublicHolidayCalendar.Status.DISABLED);
            verify(client).find(year);
        }
        for (int year : new int[] {2025, 2029, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThat(service.getHolidays(year).status()).isEqualTo(PublicHolidayCalendar.Status.OUT_OF_RANGE);
        }
        verifyNoMoreInteractions(client);
    }
}
