package com.personal.baton.adapter.in.web.holiday;

import com.personal.baton.application.holiday.PublicHolidayCalendar;
import com.personal.baton.application.holiday.port.in.GetPublicHolidaysUseCase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicHolidayController {
    public static final String PATH = "/api/v1/calendar/holidays";
    private final GetPublicHolidaysUseCase holidays;

    public PublicHolidayController(GetPublicHolidaysUseCase holidays) {
        this.holidays = holidays;
    }

    @GetMapping(PATH)
    public ResponseEntity<HolidayResponse> get(@RequestParam int year) {
        var result = holidays.getHolidays(year);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new HolidayResponse(
                result.year(), result.status(), result.checkedAt(),
                result.holidays().stream().map(item -> new HolidayItem(item.date(), item.name())).toList()));
    }

    public record HolidayResponse(int year, PublicHolidayCalendar.Status status,
            Instant checkedAt, List<HolidayItem> holidays) {}
    public record HolidayItem(LocalDate date, String name) {}
}
