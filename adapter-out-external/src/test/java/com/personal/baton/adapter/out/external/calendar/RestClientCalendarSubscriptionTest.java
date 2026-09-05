package com.personal.baton.adapter.out.external.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.personal.baton.application.calendar.port.out.CalendarSubscriptionClient.Outcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientCalendarSubscriptionTest {
    private static final UUID ID = UUID.fromString("80000000-0000-0000-0000-000000000001");
    private static final UUID SEASON = UUID.fromString("80000000-0000-0000-0000-000000000002");
    private static final String BASE = "https://cal.internal";
    private static final String PATH = BASE + "/internal/api/v1/subscriptions/" + ID;
    private MockRestServiceServer server;
    private RestClientCalendarClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl(BASE).defaultHeader("Authorization", "Bearer internal-test-only");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientCalendarClient(builder.build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/pilot"})
    @DisplayName("ID 지정 발급은 경로 접두사가 있는 공개 주소도 후보 스키마에 따라 수신한다")
    void validatesCreateContract(String prefix) throws Exception {
        String response = """
                {"subscriptionId":"%s","token":"%s","feedUrl":"https://cal.b4ton.com%s/calendars/v1/%s.ics"}
                """.formatted(ID, "a".repeat(43), prefix, "a".repeat(43));
        validate("subscription-credential", response);
        server.expect(requestTo(PATH)).andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer internal-test-only"))
                .andExpect(content().json("{\"seasonId\":\"" + SEASON + "\"}"))
                .andExpect(request -> validate("subscription-create", ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(response));
        var result = client.create(ID, SEASON);
        assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
        assertThat(result.toString()).doesNotContain("a".repeat(43));
        server.verify();
    }

    @Test
    @DisplayName("이미 생성된 구독과 연결 실패는 자동 재발급 없이 구분한다")
    void classifiesLostCreation() {
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"SUBSCRIPTION_ALREADY_EXISTS\"}"));
        server.expect(requestTo(PATH)).andRespond(withException(new SocketTimeoutException()));
        assertThat(client.create(ID, SEASON).outcome()).isEqualTo(Outcome.ALREADY_EXISTS);
        assertThat(client.create(ID, SEASON).outcome()).isEqualTo(Outcome.UNAVAILABLE);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://cal.b4ton.com/calendars/v1/", "https://cal.b4ton.com/unexpected/", "https://user@cal.b4ton.com/calendars/v1/"})
    @DisplayName("재발급 응답의 안전하지 않거나 계약과 다른 주소를 반환하지 않는다")
    void rejectsInvalidCredential(String prefix) {
        server.expect(requestTo(PATH + "/rotate")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"subscriptionId\":\"" + ID + "\",\"token\":\"" + "a".repeat(43)
                        + "\",\"feedUrl\":\"" + prefix + "a".repeat(43) + ".ics\"}", MediaType.APPLICATION_JSON));
        assertThat(client.rotate(ID).outcome()).isEqualTo(Outcome.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("구독 상태 조회는 요청 ID와 필수 세대 판정이 맞는 응답만 채택한다")
    void rejectsWrongIdentityAndMissingGeneration() {
        server.expect(requestTo(PATH)).andRespond(withSuccess("{\"subscriptionId\":\"" + SEASON + "\",\"seasonId\":\"" + SEASON + "\",\"status\":\"ACTIVE\",\"generationMatches\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(PATH)).andRespond(withSuccess("{\"subscriptionId\":\"" + ID + "\",\"seasonId\":\"" + SEASON + "\",\"status\":\"ACTIVE\"}", MediaType.APPLICATION_JSON));
        assertThat(client.findSubscription(ID).outcome()).isEqualTo(Outcome.INVALID_RESPONSE);
        assertThat(client.findSubscription(ID).outcome()).isEqualTo(Outcome.INVALID_RESPONSE);
    }

    private static void validate(String name, String body) throws java.io.IOException {
        var properties = new Properties();
        try (var input = RestClientCalendarSubscriptionTest.class.getResourceAsStream("/baton-cal/candidate/source.properties")) {
            properties.load(input);
        }
        String filename = name + ".v1.schema.json";
        byte[] bytes;
        try (var input = RestClientCalendarSubscriptionTest.class.getResourceAsStream("/baton-cal/candidate/schemas/" + filename)) {
            bytes = input.readAllBytes();
        }
        try {
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(properties.getProperty(filename));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(new String(bytes, StandardCharsets.UTF_8), InputFormat.JSON);
        assertThat(schema.validate(body, InputFormat.JSON)).isEmpty();
    }
}
