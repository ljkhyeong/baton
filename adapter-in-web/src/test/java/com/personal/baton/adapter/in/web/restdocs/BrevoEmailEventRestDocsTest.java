package com.personal.baton.adapter.in.web.restdocs;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.epages.restdocs.apispec.ConstrainedFields;
import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.config.BrevoWebhookSecurityConfig;
import com.personal.baton.adapter.in.web.config.SecurityConfig;
import com.personal.baton.adapter.in.web.identity.BrevoEmailEventController;
import com.personal.baton.application.identity.EmailDeliveryEvent;
import com.personal.baton.application.identity.port.in.ReceiveEmailDeliveryReceiptUseCase;
import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

@Tag("restdocs")
@WebMvcTest(BrevoEmailEventController.class)
@Import({SecurityConfig.class, BrevoWebhookSecurityConfig.class})
@TestPropertySource(properties = {
        "baton.brevo.webhook.enabled=true",
        "baton.brevo.webhook.bearer-token=brevo-receiver-token-at-least-32-characters"
})
@ExtendWith(RestDocumentationExtension.class)
class BrevoEmailEventRestDocsTest {

    private static final String TOKEN = "Bearer brevo-receiver-token-at-least-32-characters";
    private static final String DESCRIPTION = "Brevo SMTP 전달 결과를 전용 Bearer로 수신한다. 같은 메일·결과는 중복 저장하지 않는다.";
    private static final String SUMMARY = "Brevo 메일 전달 결과 수신";
    private static final String BODY = """
            {"event":"hard_bounce","X-Mailin-custom":"baton-delivery-id:42","ts_event":1789196400}
            """;

    @Autowired private WebApplicationContext context;
    @MockitoBean private ReceiveEmailDeliveryReceiptUseCase useCase;
    @MockitoBean private ValidateAccountSessionUseCase sessions;
    @MockitoBean private PasswordEncoder passwordEncoder;
    private MockMvc mvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider documentation) {
        mvc = webAppContextSetup(context).apply(springSecurity())
                .apply(documentationConfiguration(documentation)).build();
    }

    @Test
    @DisplayName("Brevo 전용 토큰으로 CSRF 없이 전달 결과를 접수한다")
    void acceptsReceipt() throws Exception {
        var fields = new ConstrainedFields(BrevoEmailEventController.EmailEventRequest.class);
        mvc.perform(post(BrevoEmailEventController.PATH).header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent())
                .andDo(MockMvcRestDocumentationWrapper.document("receiveBrevoEmailEvent", DESCRIPTION, SUMMARY,
                        requestHeaders(headerWithName("Authorization").description("Brevo 전용 Bearer 토큰")),
                        requestFields(
                                fields.withPath("event").description("delivered, soft_bounce, hard_bounce, blocked, invalid_email, error, deferred, spam. 나머지는 무시"),
                                fields.addConstraints(fieldWithPath("X-Mailin-custom").optional()
                                        .description("SMTP 헤더 baton-delivery-id:<발송 ID>. 없으면 무시"), "correlation"),
                                fields.addConstraints(fieldWithPath("ts_event")
                                        .description("UTC 이벤트 시각, Unix 초 정수 1..253402300799"), "timestamp"))));
        verify(useCase).receive(42L, EmailDeliveryEvent.HARD_BOUNCE, Instant.ofEpochSecond(1789196400));
        verifyNoInteractions(sessions);
    }

    @Test
    @DisplayName("틀린 Brevo 토큰은 본문을 처리하기 전에 401로 거부한다")
    void rejectsInvalidToken() throws Exception {
        mvc.perform(post(BrevoEmailEventController.PATH).header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andDo(MockMvcRestDocumentationWrapper.document("receiveBrevoEmailEventUnauthorized", DESCRIPTION, SUMMARY,
                        responseHeaders(headerWithName("WWW-Authenticate").description("Bearer 인증 필요")),
                        responseFields(fieldWithPath("code").description("UNAUTHORIZED"),
                                fieldWithPath("message").description("사용자용 오류 설명"))));
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("발송 ID 형식이 잘못되면 400으로 거부한다")
    void rejectsInvalidDeliveryId() throws Exception {
        mvc.perform(post(BrevoEmailEventController.PATH).header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("baton-delivery-id:42", "baton-delivery-id:0")))
                .andExpect(status().isBadRequest())
                .andDo(MockMvcRestDocumentationWrapper.document("receiveBrevoEmailEventInvalidInput", DESCRIPTION, SUMMARY,
                        responseFields(fieldWithPath("code").description("INVALID_INPUT"),
                                fieldWithPath("message").description("사용자용 오류 설명"))));
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @ValueSource(longs = {2200000000L, 253402300799L})
    @DisplayName("2038년 이후와 DB 상한의 이벤트 시각을 정수 초로 받는다")
    void acceptsLongTimestamp(long timestamp) throws Exception {
        mvc.perform(post(BrevoEmailEventController.PATH).header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("1789196400", Long.toString(timestamp))))
                .andExpect(status().isNoContent());
        verify(useCase).receive(42L, EmailDeliveryEvent.HARD_BOUNCE, Instant.ofEpochSecond(timestamp));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 253402300800L})
    @DisplayName("DB에 저장할 수 없는 이벤트 시각은 400으로 거부한다")
    void rejectsOutOfRangeTimestamp(long timestamp) throws Exception {
        mvc.perform(post(BrevoEmailEventController.PATH).header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("1789196400", Long.toString(timestamp))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(useCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"event\":\"opened\",\"X-Mailin-custom\":\"baton-delivery-id:42\",\"ts_event\":1789196400}",
            "{\"event\":\"delivered\",\"email\":\"other@example.com\",\"ts_event\":1789196400}"
    })
    @DisplayName("열람 추적과 발송 ID가 없는 다른 메일의 이벤트는 무시한다")
    void ignoresUnrelatedEvents(String body) throws Exception {
        mvc.perform(post(BrevoEmailEventController.PATH).header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        verifyNoInteractions(useCase);
    }
}
