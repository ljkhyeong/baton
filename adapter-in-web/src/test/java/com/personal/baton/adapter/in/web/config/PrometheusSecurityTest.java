package com.personal.baton.adapter.in.web.config;

import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PrometheusSecurityTestController.class)
@Import(SecurityConfig.class)
class PrometheusSecurityTest {

    @MockitoBean
    private ValidateAccountSessionUseCase validateAccountSessionUseCase;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @DisplayName("Prometheus 지표는 애플리케이션 컨테이너의 루프백 요청에만 응답한다")
    @Test
    void permitsPrometheusFromApplicationLoopback() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(content().string("baton_integration_metrics_refresh_success 1\n"));
    }

    @DisplayName("Prometheus 지표는 루프백이 아닌 요청을 거부한다")
    @Test
    void rejectsPrometheusFromNonLoopbackAddress() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }
}

@RestController
class PrometheusSecurityTestController {

    @GetMapping(value = "/actuator/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
    String scrape() {
        return "baton_integration_metrics_refresh_success 1\n";
    }
}
