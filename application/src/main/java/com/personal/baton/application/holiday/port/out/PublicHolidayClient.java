package com.personal.baton.application.holiday.port.out;

import com.personal.baton.application.holiday.PublicHolidayCalendar;

public interface PublicHolidayClient {
    PublicHolidayCalendar find(int year);
}
