package com.personal.baton.adapter.in.web.restdocs;

import static org.mockito.Mockito.*;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.restdocs.request.RequestDocumentation.*;
import static org.springframework.restdocs.headers.HeaderDocumentation.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.epages.restdocs.apispec.EnumFields;
import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.holiday.PublicHolidayController;
import com.personal.baton.application.holiday.PublicHolidayCalendar;
import com.personal.baton.application.holiday.PublicHolidayCalendar.Status;
import com.personal.baton.application.holiday.port.in.GetPublicHolidaysUseCase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class PublicHolidayRestDocsTest {
    private final GetPublicHolidaysUseCase useCase = mock(GetPublicHolidaysUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setup(RestDocumentationContextProvider documentation) {
        mvc = MockMvcBuilders.standaloneSetup(new PublicHolidayController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new RequestIdFilter())
                .apply(documentationConfiguration(documentation)).build();
    }

    @Test
    @DisplayName("공휴일 조회는 연도·상태·확인 시각과 날짜별 이름을 반환한다")
    void documentsPublicHolidays() throws Exception {
        when(useCase.getHolidays(2026)).thenReturn(new PublicHolidayCalendar(2026, Status.READY,
                Instant.parse("2026-09-07T01:00:00Z"), List.of(new PublicHolidayCalendar.Holiday(LocalDate.of(2026, 10, 9), "한글날"))));
        mvc.perform(get(PublicHolidayController.PATH).queryParam("year", "2026"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.holidays[0].date").value("2026-10-09"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andDo(MockMvcRestDocumentationWrapper.document("getPublicHolidays",
                        "한국천문연구원 공휴일을 조회한다. 한국 시각 기준 작년부터 내년까지 지원한다.", "대한민국 공휴일 조회",
                        queryParameters(parameterWithName("year").description("조회 연도")),
                        responseHeaders(headerWithName("Cache-Control").description("브라우저 응답 저장 금지"),
                                headerWithName("X-Request-ID").description("요청 추적 ID")),
                        responseFields(fieldWithPath("year").description("조회 연도"),
                                new EnumFields(Status.class).withPath("status").description("READY: 조회 성공, UNAVAILABLE: 공급자 장애·미발표, DISABLED: 연동 꺼짐, OUT_OF_RANGE: 지원 연도 밖"),
                                fieldWithPath("checkedAt").type(JsonFieldType.STRING).optional().description("공급자 조회 성공 시각(UTC), 성공 자료가 없으면 null"),
                                fieldWithPath("holidays").description("공휴일 목록, READY가 아니면 빈 배열"),
                                fieldWithPath("holidays[].date").description("대한민국 공휴일 날짜(YYYY-MM-DD)"),
                                fieldWithPath("holidays[].name").description("공휴일 이름"))));
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"DISABLED", "UNAVAILABLE", "OUT_OF_RANGE"})
    @DisplayName("조회할 수 없는 상태는 빈 목록과 구분 가능한 상태로 응답한다")
    void distinguishesUnavailableStates(Status value) throws Exception {
        when(useCase.getHolidays(2026)).thenReturn(PublicHolidayCalendar.empty(2026, value));
        mvc.perform(get(PublicHolidayController.PATH).queryParam("year", "2026"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(value.name()))
                .andExpect(jsonPath("$.holidays").isEmpty()).andExpect(jsonPath("$.checkedAt").doesNotExist());
    }

    @Test
    @DisplayName("숫자가 아닌 연도는 기존 입력 오류 계약으로 거부한다")
    void rejectsMalformedYear() throws Exception {
        mvc.perform(get(PublicHolidayController.PATH).queryParam("year", "invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        verifyNoInteractions(useCase);
    }
}
