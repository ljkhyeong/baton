package com.personal.baton.adapter.out.external.holiday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import com.personal.baton.application.holiday.PublicHolidayCalendar.Holiday;
import com.personal.baton.application.holiday.PublicHolidayCalendar.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.xml.SourceHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KasiPublicHolidayClientTest {
    private static final String URL = "https://apis.data.go.kr/SpcdeInfoService/getRestDeInfo"
            + "?ServiceKey=test%2Bkey%2F%3D&solYear=2026&numOfRows=100&pageNo=1";
    private final RestClient.Builder builder = RestClient.builder()
            .baseUrl("https://apis.data.go.kr/SpcdeInfoService")
            .configureMessageConverters(converters -> converters.addCustomConverter(new SourceHttpMessageConverter<>()));
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AtomicLong ticker = new AtomicLong();
    private final KasiPublicHolidayClient client = new KasiPublicHolidayClient(builder.build(), "test+key/=",
            Clock.fixed(Instant.parse("2026-09-07T01:00:00Z"), ZoneOffset.UTC), ticker::get);

    @Test
    @DisplayName("공식 XML의 공휴일만 날짜순으로 반환하고 같은 연도는 한 시간 동안 재조회하지 않는다")
    void readsAndCachesHolidays() {
        server.expect(requestTo(URL)).andRespond(withSuccess(response(3,
                item("20261009", "한글날", "Y") + item("20260501", "근로자의 날", "N")
                        + item("20261003", "개천절", "Y")), MediaType.APPLICATION_XML));
        var first = client.find(2026);
        assertThat(first.status()).isEqualTo(Status.READY);
        assertThat(first.checkedAt()).isEqualTo(Instant.parse("2026-09-07T01:00:00Z"));
        assertThat(first.holidays()).containsExactly(new Holiday(LocalDate.of(2026, 10, 3), "개천절"),
                new Holiday(LocalDate.of(2026, 10, 9), "한글날"));
        ticker.set(Duration.ofHours(1).toNanos() - 1);
        assertThat(client.find(2026)).isSameAs(first);
        server.verify();
    }

    @Test
    @DisplayName("공급자 오류도 캐시하고 한 시간 뒤 다음 요청에서 다시 조회한다")
    void throttlesFailuresAndRefreshes() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withSuccess(response(1,
                item("20261009", "한글날", "Y")), MediaType.APPLICATION_XML));
        assertThat(client.find(2026).status()).isEqualTo(Status.UNAVAILABLE);
        ticker.set(Duration.ofHours(1).toNanos() - 1);
        assertThat(client.find(2026).status()).isEqualTo(Status.UNAVAILABLE);
        ticker.incrementAndGet();
        assertThat(client.find(2026).status()).isEqualTo(Status.READY);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>",
            "<response><header><resultCode>22</resultCode></header></response>",
            "<response><header><resultCode>00</resultCode></header><body><totalCount>0</totalCount><items/></body></response>",
            "<response><header><resultCode>00</resultCode></header><body><totalCount>2</totalCount><items/></body></response>"
    })
    @DisplayName("인증 오류와 미발표·불완전 응답을 공휴일 없음으로 처리하지 않는다")
    void rejectsUnavailableData(String xml) {
        server.expect(requestTo(URL)).andRespond(withSuccess(xml, MediaType.APPLICATION_XML));
        var result = client.find(2026);
        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.checkedAt()).isNull();
        assertThat(result.holidays()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"20260230", "20271009", "invalid"})
    @DisplayName("잘못된 날짜와 다른 연도 응답은 화면에 전달하지 않는다")
    void rejectsInvalidDates(String date) {
        server.expect(requestTo(URL)).andRespond(withSuccess(response(1, item(date, "휴일", "Y")), MediaType.APPLICATION_XML));
        assertThat(client.find(2026).status()).isEqualTo(Status.UNAVAILABLE);
    }

    private static String response(int count, String items) {
        return "<response><header><resultCode>00</resultCode></header><body><totalCount>" + count
                + "</totalCount><items>" + items + "</items></body></response>";
    }

    private static String item(String date, String name, String holiday) {
        return "<item><locdate>" + date + "</locdate><dateName>" + name + "</dateName><isHoliday>"
                + holiday + "</isHoliday></item>";
    }
}
