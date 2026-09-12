package com.personal.baton.adapter.in.web.config;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.baton.adapter.in.web.identity.BrevoEmailEventController;
import com.personal.baton.application.identity.port.in.ReceiveEmailDeliveryReceiptUseCase;
import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BrevoEmailEventController.class)
@Import({SecurityConfig.class, BrevoWebhookSecurityConfig.class})
class DisabledBrevoWebhookSecurityTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ReceiveEmailDeliveryReceiptUseCase useCase;
    @MockitoBean private ValidateAccountSessionUseCase sessions;
    @MockitoBean private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("Brevo 수신은 기본 비활성이며 임의의 토큰과 잘못된 본문도 처리하지 않는다")
    void rejectsDisabledWebhook() throws Exception {
        mvc.perform(post(BrevoEmailEventController.PATH).header("Authorization", "Bearer any-token")
                        .contentType("application/json").content("{invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(useCase);
    }
}
