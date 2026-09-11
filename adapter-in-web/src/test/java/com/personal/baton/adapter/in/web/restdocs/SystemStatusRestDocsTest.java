package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.system.SystemStatusController;
import com.personal.baton.application.system.port.in.GetSystemStatusUseCase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class SystemStatusRestDocsTest {

    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");

    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        GetSystemStatusUseCase useCase = mock(GetSystemStatusUseCase.class);
        when(useCase.getStatus()).thenReturn(new GetSystemStatusUseCase.SystemStatusResult(
                "baton",
                Instant.parse("2026-07-20T12:00:00Z")
        ));

        mockMvc = MockMvcBuilders.standaloneSetup(new SystemStatusController(useCase))
                .addFilters(new RequestIdFilter(() -> REQUEST_ID))
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(prettyPrint())
                        .withResponseDefaults(prettyPrint()))
                .alwaysExpect(header().string(
                        RequestIdFilter.HEADER_NAME,
                        REQUEST_ID.toString()
                ))
                .alwaysDo(document("{class-name}/{method-name}"))
                .build();
    }

    @DisplayName("시스템 상태 API는 서비스 이름과 확인 시각을 반환한다")
    @Test
    void documentsSystemStatus() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("baton"))
                .andDo(MockMvcRestDocumentationWrapper.document(
                        "getSystemStatus",
                        "BATON 서비스 이름과 서버 확인 시각을 조회한다.",
                        "시스템 상태 조회",
                        responseHeaders(
                                headerWithName(RequestIdFilter.HEADER_NAME)
                                        .description("서버가 생성한 요청 추적 ID")
                        ),
                        responseFields(
                                fieldWithPath("service").description("서비스 식별자"),
                                fieldWithPath("checkedAt").description("상태 확인 시각(UTC)")
                        )));
    }
}
