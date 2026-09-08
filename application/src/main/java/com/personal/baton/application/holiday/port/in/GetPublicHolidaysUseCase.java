package com.personal.baton.application.holiday.port.in;

import com.personal.baton.application.holiday.PublicHolidayCalendar;

public interface GetPublicHolidaysUseCase {
    PublicHolidayCalendar getHolidays(int year);
}
