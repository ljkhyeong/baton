package com.personal.baton.application.holiday;

import com.personal.baton.application.holiday.port.in.GetPublicHolidaysUseCase;
import com.personal.baton.application.holiday.port.out.PublicHolidayClient;
import java.time.Clock;
import java.time.Year;
import java.time.ZoneId;
import org.springframework.stereotype.Service;

@Service
public class PublicHolidayService implements GetPublicHolidaysUseCase {
    private final PublicHolidayClient client;
    private final Clock clock;

    public PublicHolidayService(PublicHolidayClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public PublicHolidayCalendar getHolidays(int year) {
        int currentYear = Year.now(clock.withZone(ZoneId.of("Asia/Seoul"))).getValue();
        if (year < currentYear - 1 || year > currentYear + 1) {
            return PublicHolidayCalendar.empty(year, PublicHolidayCalendar.Status.OUT_OF_RANGE);
        }
        return client.find(year);
    }
}
