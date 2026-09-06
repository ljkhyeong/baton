package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.workspace.ResourceLinkPreviewController;
import com.personal.baton.application.workspace.port.in.ResourceLinkPreviewUseCase;
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
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class ResourceLinkPreviewRestDocsTest {
    private MockMvc mvc;
    private ResourceLinkPreviewUseCase previews;
    private final UUID team = UUID.randomUUID();
    private final UUID season = UUID.randomUUID();

    @BeforeEach
    void setUp(RestDocumentationContextProvider docs) {
        previews = mock(ResourceLinkPreviewUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(new ResourceLinkPreviewController(previews))
                .setControllerAdvice(new GlobalExceptionHandler())
                .apply(documentationConfiguration(docs)).build();
    }

    @Test @DisplayName("접근 가능한 시즌에서 링크 제목과 썸네일을 조회한다")
    void documentsPreview() throws Exception {
        String url = "https://youtu.be/dQw4w9WgXcQ";
        when(previews.preview(team, season, "key", url)).thenReturn(new ResourceLinkPreviewUseCase.Preview(
                "소개 영상", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"));
        mvc.perform(get(ResourceLinkPreviewController.PATH, team, season).header("X-Baton-Access-Key", "key").param("url", url))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.title").value("소개 영상"))
                .andDo(MockMvcRestDocumentationWrapper.document("getResourceLinkPreview",
                        "YouTube·Vimeo의 공개 영상 제목과 썸네일을 조회한다. 미지원 링크·외부 오류는 두 필드가 null이며 수동 등록을 계속할 수 있다. 시즌 읽기 권한이 필요하다.", "자료 링크 정보 조회",
                        pathParameters(parameterWithName("teamId").description("팀 식별자"), parameterWithName("seasonId").description("시즌 식별자")),
                        requestHeaders(headerWithName("X-Baton-Access-Key").description("공유 키 팀의 접근 키. 계정 팀은 로그인 세션 사용").optional()),
                        queryParameters(parameterWithName("url").description("등록할 링크. 공백 제외 필수, 최대 2048자")),
                        responseHeaders(headerWithName("Cache-Control").description("응답 캐시 금지")),
                        responseFields(fieldWithPath("title").type(JsonFieldType.STRING).optional().description("최대 200자의 제목. 조회 불가 시 null"),
                                fieldWithPath("thumbnailUrl").type(JsonFieldType.STRING).optional().description("YouTube·Vimeo HTTPS 이미지 주소. 없으면 null"))));
        verify(previews).preview(team, season, "key", url);
    }

    @Test @DisplayName("빈 링크는 외부 조회 전에 거부한다")
    void rejectsEmptyUrl() throws Exception {
        mvc.perform(get(ResourceLinkPreviewController.PATH, team, season).param("url", " "))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(previews);
    }
}
