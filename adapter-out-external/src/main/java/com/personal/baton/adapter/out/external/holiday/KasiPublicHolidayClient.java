package com.personal.baton.adapter.out.external.holiday;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.personal.baton.application.holiday.PublicHolidayCalendar;
import com.personal.baton.application.holiday.PublicHolidayCalendar.Holiday;
import com.personal.baton.application.holiday.PublicHolidayCalendar.Status;
import com.personal.baton.application.holiday.port.out.PublicHolidayClient;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import javax.xml.transform.dom.DOMSource;
import org.springframework.http.MediaType;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.converter.xml.SourceHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public final class KasiPublicHolidayClient implements PublicHolidayClient {
    private final RestClient restClient;
    private final String serviceKey;
    private final Clock clock;
    private final Cache<Integer, PublicHolidayCalendar> cache;

    public KasiPublicHolidayClient(RestClient restClient, String serviceKey, Clock clock) {
        this(restClient, serviceKey, clock, Ticker.systemTicker());
    }

    KasiPublicHolidayClient(RestClient restClient, String serviceKey, Clock clock, Ticker ticker) {
        this.restClient = restClient;
        this.serviceKey = serviceKey;
        this.clock = clock;
        this.cache = Caffeine.newBuilder().maximumSize(3)
                .expireAfterWrite(Duration.ofHours(1)).ticker(ticker).build();
    }

    @Override
    public PublicHolidayCalendar find(int year) {
        // 실패 결과도 캐시해 장애나 호출 한도 초과 때 요청이 반복되지 않게 한다.
        return cache.get(year, this::fetch);
    }

    private PublicHolidayCalendar fetch(int year) {
        try {
            DOMSource source = restClient.get()
                    .uri(builder -> builder.path("/getRestDeInfo")
                            .queryParam("ServiceKey", "{key}").queryParam("solYear", year)
                            .queryParam("numOfRows", 100).queryParam("pageNo", 1).build(serviceKey))
                    .accept(MediaType.APPLICATION_XML).retrieve().body(DOMSource.class);
            if (source == null) return PublicHolidayCalendar.empty(year, Status.UNAVAILABLE);
            Element root = ((Document) source.getNode()).getDocumentElement();
            if (!"response".equals(root.getTagName()) || !"00".equals(text(root, "resultCode"))) {
                return PublicHolidayCalendar.empty(year, Status.UNAVAILABLE);
            }
            var items = root.getElementsByTagName("item");
            int total = Integer.parseInt(text(root, "totalCount"));
            // 연간 자료가 없거나 일부 페이지만 왔으면 공휴일이 없다고 표시하지 않는다.
            if (total == 0 || total != items.getLength()) {
                return PublicHolidayCalendar.empty(year, Status.UNAVAILABLE);
            }
            var holidays = new ArrayList<Holiday>();
            for (int index = 0; index < items.getLength(); index++) {
                Element item = (Element) items.item(index);
                String isHoliday = text(item, "isHoliday");
                if ("N".equals(isHoliday)) continue;
                LocalDate date = LocalDate.parse(text(item, "locdate"), DateTimeFormatter.BASIC_ISO_DATE);
                String name = text(item, "dateName");
                if (!"Y".equals(isHoliday) || date.getYear() != year || name.isBlank()) {
                    return PublicHolidayCalendar.empty(year, Status.UNAVAILABLE);
                }
                holidays.add(new Holiday(date, name));
            }
            return new PublicHolidayCalendar(year, Status.READY, clock.instant(), holidays.stream()
                    .distinct().sorted(Comparator.comparing(Holiday::date).thenComparing(Holiday::name)).toList());
        } catch (RestClientException | IllegalArgumentException | DateTimeException exception) {
            // 공급자 오류에는 서비스 키가 든 URL이 포함될 수 있어 예외 원문을 기록하지 않는다.
            return PublicHolidayCalendar.empty(year, Status.UNAVAILABLE);
        }
    }

    private static String text(Element element, String tag) {
        var node = element.getElementsByTagName(tag).item(0);
        return node == null ? "" : node.getTextContent().strip();
    }

    @Component
    public static class Factory {
        private final RestClient.Builder builder;
        private final ClientHttpRequestFactoryBuilder<?> factoryBuilder;
        private final HttpClientSettings settings;

        public Factory(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> factoryBuilder,
                HttpClientSettings settings) {
            this.builder = builder;
            this.factoryBuilder = factoryBuilder;
            this.settings = settings;
        }

        public KasiPublicHolidayClient create(String serviceKey, Clock clock) {
            var client = builder.clone()
                    .baseUrl("https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService")
                    .requestFactory(factoryBuilder.build(settings
                            .withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(3))
                            .withRedirects(HttpRedirects.DONT_FOLLOW)))
                    .configureMessageConverters(converters -> converters.addCustomConverter(new SourceHttpMessageConverter<>()))
                    .build();
            return new KasiPublicHolidayClient(client, serviceKey, clock);
        }
    }
}
