package com.personal.baton.adapter.in.web.holiday;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.baton.adapter.in.web.config.SecurityConfig;
import com.personal.baton.application.holiday.PublicHolidayCalendar;
import com.personal.baton.application.holiday.port.in.GetPublicHolidaysUseCase;
import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicHolidayController.class)
@Import(SecurityConfig.class)
class PublicHolidaySecurityTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private GetPublicHolidaysUseCase holidays;
    @MockitoBean private ValidateAccountSessionUseCase sessions;
    @MockitoBean private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("공개 공휴일 조회에는 계정 세션이나 팀 접근 키가 필요하지 않다")
    void permitsPublicLookup() throws Exception {
        when(holidays.getHolidays(2026)).thenReturn(PublicHolidayCalendar.empty(2026, PublicHolidayCalendar.Status.DISABLED));
        mvc.perform(get(PublicHolidayController.PATH).queryParam("year", "2026")).andExpect(status().isOk());
    }
}
