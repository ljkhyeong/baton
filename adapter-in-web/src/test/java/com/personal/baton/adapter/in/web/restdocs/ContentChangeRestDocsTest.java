package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.epages.restdocs.apispec.EnumFields;
import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.workspace.ContentChangeController;
import com.personal.baton.application.workspace.port.in.ContentChangeUseCase;
import com.personal.baton.application.workspace.port.in.ContentChangeUseCase.ContentHistoryResult;
import com.personal.baton.application.workspace.port.in.ContentChangeUseCase.ContentChangeResult;
import com.personal.baton.application.workspace.port.in.ContentChangeUseCase.FieldChangeResult;
import com.personal.baton.domain.workspace.ContentRecordKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.restdocs.headers.HeaderDocumentation.*;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.restdocs.request.RequestDocumentation.*;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.*;
import static org.springframework.restdocs.snippet.Attributes.key;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class ContentChangeRestDocsTest {
    private final UUID team = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private final UUID season = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private final UUID record = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private MockMvc mvc;
    private ContentChangeUseCase useCase;
    @BeforeEach void setUp(RestDocumentationContextProvider documentation) {
        useCase = mock(ContentChangeUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new ContentChangeController(useCase)).setControllerAdvice(new GlobalExceptionHandler())
                .apply(documentationConfiguration(documentation)).build();
    }
    @Test @DisplayName("결정 수정 이력은 변경자와 필드별 변경 전후를 반환한다")
    void documentsDecisionChanges() throws Exception { document(ContentRecordKind.DECISION, ContentChangeController.DECISION_PATH, "getDecisionChanges"); }
    @Test @DisplayName("자료 수정 이력은 자료에 속한 변경 기록만 반환한다")
    void documentsResourceChanges() throws Exception { document(ContentRecordKind.ROLE_RESOURCE, ContentChangeController.RESOURCE_PATH, "getRoleResourceChanges"); }
    private void document(ContentRecordKind kind, String path, String operation) throws Exception {
        when(useCase.getHistory(team, season, kind, record, "key")).thenReturn(new ContentHistoryResult(team, season, kind, record,
                List.of(new ContentChangeResult(UUID.fromString("00000000-0000-4000-8000-000000000004"), null, "공유 키 사용자", Instant.parse("2026-09-05T03:00:00Z"),
                        List.of(new FieldChangeResult("제목", "운영안", "수정한 운영안"))))));
        mvc.perform(get(path, team, season, record).header("X-Baton-Access-Key", "key"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.recordKind").value(kind.name()))
                .andExpect(jsonPath("$.changes[0].fields[0].beforeValue").value("운영안"))
                .andExpect(jsonPath("$.changes[0].actorAccountId").isEmpty())
                .andExpect(jsonPath("$.changes[0].changedAt").value("2026-09-05T03:00:00Z"))
                .andDo(MockMvcRestDocumentationWrapper.document(operation, "현재 접근 권한으로 기록의 최근 50건 수정·보관·복원 이력을 조회한다.", "기록 수정 이력 조회",
                        pathParameters(parameterWithName("teamId").description("팀 식별자"), parameterWithName("seasonId").description("시즌 식별자"),
                                parameterWithName("recordId").description("원본 기록 식별자")),
                        requestHeaders(headerWithName("X-Baton-Access-Key").description("공유 키 팀의 접근 키").optional()),
                        responseHeaders(headerWithName("Cache-Control").description("기록 캐시 금지")),
                        responseFields(fieldWithPath("teamId").description("팀 식별자"), fieldWithPath("seasonId").description("시즌 식별자"),
                                fieldWithPath("recordId").description("원본 기록 식별자"), new EnumFields(ContentRecordKind.class).withPath("recordKind").description("기록 종류"),
                                fieldWithPath("changes").type(JsonFieldType.ARRAY).attributes(key("itemsType").value(JsonFieldType.OBJECT)).description("최신순 이력"),
                                fieldWithPath("changes[].id").description("변경 식별자"), fieldWithPath("changes[].actorAccountId").type(JsonFieldType.STRING).optional().description("변경 계정. 비로그인 공유 키 사용은 null"),
                                fieldWithPath("changes[].actorName").description("변경 당시 계정 이름 또는 공유 키 사용자"), fieldWithPath("changes[].changedAt").description("변경 시각"),
                                fieldWithPath("changes[].fields").type(JsonFieldType.ARRAY).attributes(key("itemsType").value(JsonFieldType.OBJECT)).description("값이 바뀐 항목"),
                                fieldWithPath("changes[].fields[].fieldName").description("항목 이름"),
                                fieldWithPath("changes[].fields[].beforeValue").type(JsonFieldType.STRING).optional().description("변경 전 값. 값이 없으면 null"),
                                fieldWithPath("changes[].fields[].afterValue").type(JsonFieldType.STRING).optional().description("변경 후 값. 값이 없으면 null"))));
    }
}
