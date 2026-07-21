package com.personal.baton.adapter.in.web.restdocs;

import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.workspace.WorkspaceController;
import com.personal.baton.application.workspace.error.IdempotencyKeyConflictException;
import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceAccessKeyConflictException;
import com.personal.baton.application.workspace.error.WorkspaceCreationDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.error.WorkspaceRecoveryDeniedException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateDecisionCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateHandoffItemCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.DecisionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.HandoffItemResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.MemberResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoutineResult;
import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class WorkspaceRestDocsTest {

    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEASON_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MEMBER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID NEXT_MEMBER_ID = UUID.fromString("33333333-3333-3333-3333-444444444444");
    private static final UUID ROLE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ROUTINE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID DECISION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID HANDOFF_ITEM_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String ACCESS_KEY = "baton-access-key";
    private static final String NEW_ACCESS_KEY = "rotated-baton-access-key";
    private static final String IDEMPOTENCY_KEY = "workspace-idempotency-restdocs-0001";
    private static final String ACCESS_KEY_CHANGE_IDEMPOTENCY_KEY = "access-key-change-restdocs-0000001";
    private static final String CREATION_KEY = "pilot-operator-key";
    private static final String RECOVERY_KEY = "pilot-recovery-key";

    private WorkspaceUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        useCase = mock(WorkspaceUseCase.class);
        mockMvc = standaloneSetup(new WorkspaceController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(prettyPrint())
                        .withResponseDefaults(prettyPrint()))
                .build();
    }

    @DisplayName("워크스페이스 생성 API는 팀과 시즌을 만들고 원문 접근 키를 한 번 반환한다")
    @Test
    void documentsCreateWorkspace() throws Exception {
        when(useCase.createWorkspace(eq(IDEMPOTENCY_KEY), eq(CREATION_KEY), any(CreateWorkspaceCommand.class)))
                .thenReturn(new WorkspaceUseCase.CreatedWorkspaceResult(TEAM_ID, SEASON_ID, ACCESS_KEY));

        mockMvc.perform(post("/api/v1/workspaces")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Baton-Creation-Key", CREATION_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamName": "알고리즘 한 바퀴",
                                  "seasonName": "2026 여름 시즌",
                                  "startDate": "2026-07-02",
                                  "endDate": "2026-09-17",
                                  "memberNames": ["박민서", "김준호"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "/api/v1/teams/" + TEAM_ID + "/seasons/" + SEASON_ID + "/workspace"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.teamId").value(TEAM_ID.toString()))
                .andExpect(jsonPath("$.seasonId").value(SEASON_ID.toString()))
                .andExpect(jsonPath("$.accessKey").value(ACCESS_KEY))
                .andDo(document("workspace-create",
                        requestHeaders(
                                headerWithName("Idempotency-Key")
                                        .description("32~200자의 URL 안전 멱등 키"),
                                headerWithName("X-Baton-Creation-Key").optional()
                                        .description("운영 환경에서 설정한 파일럿 생성 키")
                        ),
                        requestFields(
                                fieldWithPath("teamName").description("팀 이름"),
                                fieldWithPath("seasonName").description("첫 시즌 이름"),
                                fieldWithPath("startDate").description("시즌 시작일(ISO-8601 날짜)"),
                                fieldWithPath("endDate").description("시즌 종료일(ISO-8601 날짜)"),
                                fieldWithPath("memberNames[]").description("한 명 이상의 구성원 이름")
                        ),
                        responseFields(
                                fieldWithPath("teamId").description("생성한 팀 UUID"),
                                fieldWithPath("seasonId").description("생성한 시즌 UUID"),
                                fieldWithPath("accessKey").description("이 응답에서만 제공하는 원문 접근 키")
                        )));
    }

    @DisplayName("워크스페이스 조회 API는 Today 화면에 필요한 전체 projection을 반환한다")
    @Test
    void documentsGetWorkspace() throws Exception {
        when(useCase.getWorkspace(TEAM_ID, SEASON_ID, ACCESS_KEY)).thenReturn(workspaceResult());

        mockMvc.perform(get("/api/v1/teams/{teamId}/seasons/{seasonId}/workspace", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.team.name").value("알고리즘 한 바퀴"))
                .andExpect(jsonPath("$.members[0].initials").value("박"))
                .andExpect(jsonPath("$.roles[0].responsibilities[0]").value("질문 수집"))
                .andExpect(jsonPath("$.routines[0].status").value("WAITING"))
                .andExpect(jsonPath("$.decisions[0].createdAt").value("2026-07-20T03:04:05Z"))
                .andExpect(jsonPath("$.handoffItems[0].category").value("RESOURCE"))
                .andDo(document("workspace-get",
                        workspacePathParameters(),
                        accessKeyHeader(),
                        responseFields(workspaceResponseFields())));
    }

    @DisplayName("접근 키 회전 API는 현재 키를 검증하고 새 키를 캐시할 수 없게 반환한다")
    @Test
    void documentsRotateAccessKey() throws Exception {
        when(useCase.rotateAccessKey(TEAM_ID, SEASON_ID, ACCESS_KEY_CHANGE_IDEMPOTENCY_KEY, ACCESS_KEY))
                .thenReturn(new WorkspaceUseCase.AccessKeyResult(NEW_ACCESS_KEY));

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/access-key/rotate",
                        TEAM_ID,
                        SEASON_ID)
                        .header("Idempotency-Key", ACCESS_KEY_CHANGE_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessKey").value(NEW_ACCESS_KEY))
                .andDo(document("workspace-access-key-rotate",
                        workspacePathParameters(),
                        requestHeaders(
                                headerWithName("Idempotency-Key")
                                        .description("접근 키 회전 응답을 재생할 32~200자의 멱등 키"),
                                headerWithName("X-Baton-Access-Key")
                                        .description("현재 워크스페이스 접근 키")
                        ),
                        responseFields(fieldWithPath("accessKey")
                                .description("회전 시 한 번만 제공하는 새 워크스페이스 접근 키"))));
    }

    @DisplayName("동시 접근 키 회전이 충돌하면 워크스페이스 API는 안정적인 409 오류를 반환한다")
    @Test
    void documentsWorkspaceAccessKeyConflict() throws Exception {
        when(useCase.rotateAccessKey(
                TEAM_ID,
                SEASON_ID,
                ACCESS_KEY_CHANGE_IDEMPOTENCY_KEY,
                ACCESS_KEY
        )).thenThrow(new WorkspaceAccessKeyConflictException());

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/access-key/rotate",
                        TEAM_ID,
                        SEASON_ID)
                        .header("Idempotency-Key", ACCESS_KEY_CHANGE_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_KEY_CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        "접근 키가 동시에 변경되었습니다. 최신 키로 다시 시도해 주세요"))
                .andDo(document("workspace-access-key-conflict",
                        workspacePathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("접근 키 복구 API는 운영자 키를 검증하고 새 키를 캐시할 수 없게 반환한다")
    @Test
    void documentsRecoverAccessKey() throws Exception {
        when(useCase.recoverAccessKey(TEAM_ID, SEASON_ID, ACCESS_KEY_CHANGE_IDEMPOTENCY_KEY, RECOVERY_KEY))
                .thenReturn(new WorkspaceUseCase.AccessKeyResult(NEW_ACCESS_KEY));

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/access-key/recover",
                        TEAM_ID,
                        SEASON_ID)
                        .header("Idempotency-Key", ACCESS_KEY_CHANGE_IDEMPOTENCY_KEY)
                        .header("X-Baton-Recovery-Key", RECOVERY_KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessKey").value(NEW_ACCESS_KEY))
                .andDo(document("workspace-access-key-recover",
                        workspacePathParameters(),
                        requestHeaders(
                                headerWithName("Idempotency-Key")
                                        .description("접근 키 복구 응답을 재생할 32~200자의 멱등 키"),
                                headerWithName("X-Baton-Recovery-Key")
                                        .description("설정된 파일럿 운영자 복구 키")
                        ),
                        responseFields(fieldWithPath("accessKey")
                                .description("복구 시 한 번만 제공하는 새 워크스페이스 접근 키"))));
    }

    @DisplayName("역할 생성 API는 팀 역할과 책임 목록을 저장해 반환한다")
    @Test
    void documentsCreateRole() throws Exception {
        when(useCase.createRole(eq(TEAM_ID), eq(SEASON_ID), eq(ACCESS_KEY), any(CreateRoleCommand.class)))
                .thenReturn(roleResult());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/roles", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "질문 큐레이터",
                                  "purpose": "막힌 지점을 모아 함께 풉니다",
                                  "currentMemberId": "33333333-3333-3333-3333-333333333333",
                                  "nextMemberId": "33333333-3333-3333-3333-444444444444",
                                  "assignmentStartDate": "2026-07-20",
                                  "assignmentEndDate": "2026-09-17",
                                  "responsibilities": ["질문 수집", "공통 막힘 정리"],
                                  "risk": "질문이 개인 메모에만 남을 수 있습니다"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ROLE_ID.toString()))
                .andExpect(jsonPath("$.responsibilities.length()").value(2))
                .andDo(document("workspace-role-create",
                        workspacePathParameters(),
                        accessKeyHeader(),
                        requestFields(
                                fieldWithPath("name").description("팀에서 유일한 역할 이름"),
                                fieldWithPath("purpose").description("역할의 목적"),
                                fieldWithPath("currentMemberId").optional().description("현재 담당 구성원 UUID"),
                                fieldWithPath("nextMemberId").optional().description("다음 담당 구성원 UUID"),
                                fieldWithPath("assignmentStartDate").optional().description("배정 시작일"),
                                fieldWithPath("assignmentEndDate").optional().description("배정 종료일"),
                                fieldWithPath("responsibilities[]").description("역할 책임 목록"),
                                fieldWithPath("risk").optional().description("인수인계 위험 신호")
                        ),
                        responseFields(roleResponseFields())));
    }

    @DisplayName("루틴 생성 API는 초기 상태를 WAITING으로 정해 반환한다")
    @Test
    void documentsCreateRoutine() throws Exception {
        when(useCase.createRoutine(eq(TEAM_ID), eq(SEASON_ID), eq(ACCESS_KEY), any(CreateRoutineCommand.class)))
                .thenReturn(routineResult(RoutineStatus.WAITING));

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/routines", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "모임 전 질문 모으기",
                                  "phase": "BEFORE",
                                  "dueLabel": "모임 하루 전",
                                  "ownerRoleId": "44444444-4444-4444-4444-444444444444",
                                  "detail": "공통 질문을 한 문서에 정리합니다"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andDo(document("workspace-routine-create",
                        workspacePathParameters(),
                        accessKeyHeader(),
                        requestFields(
                                fieldWithPath("title").description("루틴 제목"),
                                fieldWithPath("phase").description("실행 단계: BEFORE, DURING, AFTER"),
                                fieldWithPath("dueLabel").description("사용자에게 보일 기한 문구"),
                                fieldWithPath("ownerRoleId").description("담당 역할 UUID"),
                                fieldWithPath("detail").description("실행 방법")
                        ),
                        responseFields(routineResponseFields())));
    }

    @DisplayName("루틴 완료 API는 completed 값에 따라 DONE 상태를 반환한다")
    @Test
    void documentsUpdateRoutineCompletion() throws Exception {
        when(useCase.updateRoutineCompletion(TEAM_ID, SEASON_ID, ROUTINE_ID, ACCESS_KEY, true))
                .thenReturn(routineResult(RoutineStatus.DONE));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}/completion",
                        TEAM_ID, SEASON_ID, ROUTINE_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andDo(document("workspace-routine-completion",
                        pathParameters(
                                parameterWithName("teamId").description("팀 UUID"),
                                parameterWithName("seasonId").description("시즌 UUID"),
                                parameterWithName("routineId").description("루틴 UUID")
                        ),
                        accessKeyHeader(),
                        requestFields(fieldWithPath("completed").description("완료 여부")),
                        responseFields(routineResponseFields())));
    }

    @DisplayName("결정 생성 API는 서버 시각과 작성자 이름을 포함해 반환한다")
    @Test
    void documentsCreateDecision() throws Exception {
        when(useCase.createDecision(eq(TEAM_ID), eq(SEASON_ID), eq(ACCESS_KEY), any(CreateDecisionCommand.class)))
                .thenReturn(decisionResult());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/decisions", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "질문은 모임 전날 마감한다",
                                  "reason": "진행자가 준비할 시간을 확보합니다",
                                  "alternative": "모임 당일에도 받는 방안을 검토했습니다",
                                  "authorMemberId": "33333333-3333-3333-3333-333333333333",
                                  "roleIds": ["44444444-4444-4444-4444-444444444444"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value("2026-07-20T03:04:05Z"))
                .andExpect(jsonPath("$.authorName").value("박민서"))
                .andDo(document("workspace-decision-create",
                        workspacePathParameters(),
                        accessKeyHeader(),
                        requestFields(
                                fieldWithPath("title").description("결정 제목"),
                                fieldWithPath("reason").description("결정 이유"),
                                fieldWithPath("alternative").optional().description("검토한 대안"),
                                fieldWithPath("authorMemberId").description("작성자 구성원 UUID"),
                                fieldWithPath("roleIds[]").description("중복 없는 관련 역할 UUID 목록")
                        ),
                        responseFields(decisionResponseFields())));
    }

    @DisplayName("인수인계 항목 생성 API는 초기 완료 여부를 false로 정해 반환한다")
    @Test
    void documentsCreateHandoffItem() throws Exception {
        when(useCase.createHandoffItem(
                eq(TEAM_ID), eq(SEASON_ID), eq(ACCESS_KEY), any(CreateHandoffItemCommand.class)))
                .thenReturn(handoffItemResult(false));

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleId": "44444444-4444-4444-4444-444444444444",
                                  "label": "질문 목록 문서 권한 넘기기",
                                  "category": "RESOURCE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completed").value(false))
                .andDo(document("workspace-handoff-item-create",
                        workspacePathParameters(),
                        accessKeyHeader(),
                        requestFields(
                                fieldWithPath("roleId").description("소유 역할 UUID"),
                                fieldWithPath("label").description("인수인계할 내용"),
                                fieldWithPath("category").description(
                                        "분류: RESPONSIBILITY, ROUTINE, RESOURCE, ADVICE")
                        ),
                        responseFields(handoffItemResponseFields())));
    }

    @DisplayName("인수인계 항목 완료 API는 변경된 완료 여부를 반환한다")
    @Test
    void documentsUpdateHandoffItemCompletion() throws Exception {
        when(useCase.updateHandoffItemCompletion(TEAM_ID, SEASON_ID, HANDOFF_ITEM_ID, ACCESS_KEY, true))
                .thenReturn(handoffItemResult(true));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/completion",
                        TEAM_ID, SEASON_ID, HANDOFF_ITEM_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andDo(document("workspace-handoff-item-completion",
                        pathParameters(
                                parameterWithName("teamId").description("팀 UUID"),
                                parameterWithName("seasonId").description("시즌 UUID"),
                                parameterWithName("itemId").description("인수인계 항목 UUID")
                        ),
                        accessKeyHeader(),
                        requestFields(fieldWithPath("completed").description("완료 여부")),
                        responseFields(handoffItemResponseFields())));
    }

    @DisplayName("접근 키가 없거나 틀리면 워크스페이스 API는 403 오류 계약을 반환한다")
    @Test
    void documentsWorkspaceAccessDenied() throws Exception {
        when(useCase.getWorkspace(eq(TEAM_ID), eq(SEASON_ID), isNull()))
                .thenThrow(new WorkspaceAccessDeniedException());

        mockMvc.perform(get("/api/v1/teams/{teamId}/seasons/{seasonId}/workspace", TEAM_ID, SEASON_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("작업 공간 접근 키가 올바르지 않습니다"))
                .andDo(document("workspace-access-denied",
                        workspacePathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("멱등 키를 다른 생성 요청에 재사용하면 워크스페이스 API는 409 오류 계약을 반환한다")
    @Test
    void documentsIdempotencyKeyReused() throws Exception {
        when(useCase.createWorkspace(eq(IDEMPOTENCY_KEY), eq(CREATION_KEY), any(CreateWorkspaceCommand.class)))
                .thenThrow(new IdempotencyKeyReusedException());

        mockMvc.perform(post("/api/v1/workspaces")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Baton-Creation-Key", CREATION_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validWorkspaceRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andDo(document("workspace-idempotency-key-reused",
                        responseFields(errorResponseFields())));
    }

    @DisplayName("동일한 멱등 키의 생성 요청이 동시에 처리되면 재시도 가능한 409 오류를 반환한다")
    @Test
    void documentsIdempotencyKeyConflict() throws Exception {
        when(useCase.createWorkspace(eq(IDEMPOTENCY_KEY), eq(CREATION_KEY), any(CreateWorkspaceCommand.class)))
                .thenThrow(new IdempotencyKeyConflictException());

        mockMvc.perform(post("/api/v1/workspaces")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Baton-Creation-Key", CREATION_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validWorkspaceRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        "동일한 멱등 키의 생성 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요"))
                .andDo(document("workspace-idempotency-key-conflict",
                        responseFields(errorResponseFields())));
    }

    @DisplayName("과거 접근 키 변경 멱등 응답은 일반화된 만료 오류와 409를 반환한다")
    @Test
    void documentsIdempotencyReplayExpired() throws Exception {
        when(useCase.rotateAccessKey(
                TEAM_ID,
                SEASON_ID,
                ACCESS_KEY_CHANGE_IDEMPOTENCY_KEY,
                ACCESS_KEY
        ))
                .thenThrow(new IdempotencyReplayExpiredException());

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/access-key/rotate",
                        TEAM_ID,
                        SEASON_ID)
                        .header("Idempotency-Key", ACCESS_KEY_CHANGE_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_REPLAY_EXPIRED"))
                .andExpect(jsonPath("$.message").value(
                        "더 최신 작업이 완료되어 이 멱등 키의 응답을 더 이상 재생할 수 없습니다"))
                .andDo(document("workspace-idempotency-replay-expired",
                        workspacePathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("멱등 키가 누락되면 워크스페이스 생성 API는 안정적인 400 입력 오류를 반환한다")
    @Test
    void documentsMissingIdempotencyKey() throws Exception {
        when(useCase.createWorkspace(isNull(), eq(CREATION_KEY), any(CreateWorkspaceCommand.class)))
                .thenThrow(new DomainValidationException(
                        "멱등 키는 32자 이상 200자 이하의 URL 안전 ASCII 문자여야 합니다"
                ));

        mockMvc.perform(post("/api/v1/workspaces")
                        .header("X-Baton-Creation-Key", CREATION_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validWorkspaceRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(
                        "멱등 키는 32자 이상 200자 이하의 URL 안전 ASCII 문자여야 합니다"))
                .andDo(document("workspace-idempotency-key-invalid",
                        responseFields(errorResponseFields())));
    }

    @DisplayName("운영자 생성 키가 틀리면 워크스페이스 생성 API는 403 오류 계약을 반환한다")
    @Test
    void documentsWorkspaceCreationDenied() throws Exception {
        when(useCase.createWorkspace(eq(IDEMPOTENCY_KEY), eq("wrong-key"), any(CreateWorkspaceCommand.class)))
                .thenThrow(new WorkspaceCreationDeniedException());

        mockMvc.perform(post("/api/v1/workspaces")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Baton-Creation-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validWorkspaceRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CREATION_DENIED"))
                .andDo(document("workspace-creation-denied",
                        responseFields(errorResponseFields())));
    }

    @DisplayName("운영자 복구 키가 틀리면 접근 키 복구 API는 403 오류 계약을 반환한다")
    @Test
    void documentsWorkspaceRecoveryDenied() throws Exception {
        when(useCase.recoverAccessKey(
                TEAM_ID,
                SEASON_ID,
                ACCESS_KEY_CHANGE_IDEMPOTENCY_KEY,
                "wrong-key"
        ))
                .thenThrow(new WorkspaceRecoveryDeniedException());

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/access-key/recover",
                        TEAM_ID,
                        SEASON_ID)
                        .header("Idempotency-Key", ACCESS_KEY_CHANGE_IDEMPOTENCY_KEY)
                        .header("X-Baton-Recovery-Key", "wrong-key"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_RECOVERY_DENIED"))
                .andDo(document("workspace-recovery-denied",
                        workspacePathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("같은 팀에 역할 이름이 중복되면 역할 API는 409 오류 계약을 반환한다")
    @Test
    void documentsRoleNameConflict() throws Exception {
        when(useCase.createRole(eq(TEAM_ID), eq(SEASON_ID), eq(ACCESS_KEY), any(CreateRoleCommand.class)))
                .thenThrow(new RoleNameConflictException());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/roles", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "질문 큐레이터",
                                  "purpose": "막힌 지점을 모아 함께 풉니다",
                                  "responsibilities": []
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_CONFLICT"))
                .andDo(document("workspace-role-name-conflict",
                        workspacePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("하위 리소스가 없으면 워크스페이스 API는 식별 가능한 404 오류를 반환한다")
    @Test
    void documentsChildNotFound() throws Exception {
        when(useCase.updateRoutineCompletion(TEAM_ID, SEASON_ID, ROUTINE_ID, ACCESS_KEY, true))
                .thenThrow(new WorkspaceNotFoundException("ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다"));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}/completion",
                        TEAM_ID, SEASON_ID, ROUTINE_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTINE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("루틴을 찾을 수 없습니다"))
                .andDo(document("workspace-routine-not-found",
                        pathParameters(
                                parameterWithName("teamId").description("팀 UUID"),
                                parameterWithName("seasonId").description("시즌 UUID"),
                                parameterWithName("routineId").description("루틴 UUID")
                        ),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("정규화 후 구성원 이름이 중복되면 INVALID_INPUT과 안전한 상세를 반환한다")
    @Test
    void documentsDomainValidationError() throws Exception {
        when(useCase.createWorkspace(eq(IDEMPOTENCY_KEY), eq(CREATION_KEY), any(CreateWorkspaceCommand.class)))
                .thenThrow(new DomainValidationException("구성원 이름은 중복될 수 없습니다"));

        mockMvc.perform(post("/api/v1/workspaces")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Baton-Creation-Key", CREATION_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamName": "알고리즘 한 바퀴",
                                  "seasonName": "2026 여름 시즌",
                                  "startDate": "2026-07-02",
                                  "endDate": "2026-09-17",
                                  "memberNames": ["박민서", "  박민서  "]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("구성원 이름은 중복될 수 없습니다"))
                .andDo(document("workspace-invalid-input",
                        responseFields(errorResponseFields())));
    }

    @DisplayName("예상하지 않은 인자 오류의 내부 메시지는 HTTP 응답에 노출하지 않는다")
    @Test
    void hidesUnexpectedIllegalArgumentMessage() throws Exception {
        when(useCase.createWorkspace(eq(IDEMPOTENCY_KEY), eq(CREATION_KEY), any(CreateWorkspaceCommand.class)))
                .thenThrow(new IllegalArgumentException("jdbc:mysql://secret-host/internal"));

        mockMvc.perform(post("/api/v1/workspaces")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Baton-Creation-Key", CREATION_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamName": "알고리즘 한 바퀴",
                                  "seasonName": "2026 여름 시즌",
                                  "startDate": "2026-07-02",
                                  "endDate": "2026-09-17",
                                  "memberNames": ["박민서"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다"));
    }

    @DisplayName("UUID 경로 변수 형식이 잘못되면 안정적인 400 오류 계약을 반환한다")
    @Test
    void handlesInvalidPathVariableFormat() throws Exception {
        mockMvc.perform(get("/api/v1/teams/not-a-uuid/seasons/{seasonId}/workspace", SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("요청 값 형식이 올바르지 않습니다"));
    }

    private WorkspaceUseCase.WorkspaceResult workspaceResult() {
        return new WorkspaceUseCase.WorkspaceResult(
                new WorkspaceUseCase.TeamResult(TEAM_ID, "알고리즘 한 바퀴"),
                new WorkspaceUseCase.SeasonResult(
                        SEASON_ID,
                        "2026 여름 시즌",
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 9, 17)
                ),
                List.of(
                        new MemberResult(MEMBER_ID, "박민서", "박", "#d9e4da"),
                        new MemberResult(NEXT_MEMBER_ID, "김준호", "김", "#f1d6cc")
                ),
                List.of(roleResult()),
                List.of(routineResult(RoutineStatus.WAITING)),
                List.of(decisionResult()),
                List.of(handoffItemResult(false))
        );
    }

    private String validWorkspaceRequest() {
        return """
                {
                  "teamName": "알고리즘 한 바퀴",
                  "seasonName": "2026 여름 시즌",
                  "startDate": "2026-07-02",
                  "endDate": "2026-09-17",
                  "memberNames": ["박민서", "김준호"]
                }
                """;
    }

    private RoleResult roleResult() {
        return new RoleResult(
                ROLE_ID,
                "질문 큐레이터",
                "막힌 지점을 모아 함께 풉니다",
                MEMBER_ID,
                NEXT_MEMBER_ID,
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 9, 17),
                List.of("질문 수집", "공통 막힘 정리"),
                "질문이 개인 메모에만 남을 수 있습니다"
        );
    }

    private RoutineResult routineResult(RoutineStatus status) {
        return new RoutineResult(
                ROUTINE_ID,
                "모임 전 질문 모으기",
                RoutinePhase.BEFORE,
                "모임 하루 전",
                ROLE_ID,
                status,
                "공통 질문을 한 문서에 정리합니다"
        );
    }

    private DecisionResult decisionResult() {
        return new DecisionResult(
                DECISION_ID,
                "질문은 모임 전날 마감한다",
                "진행자가 준비할 시간을 확보합니다",
                "모임 당일에도 받는 방안을 검토했습니다",
                Instant.parse("2026-07-20T03:04:05Z"),
                "박민서",
                List.of(ROLE_ID)
        );
    }

    private HandoffItemResult handoffItemResult(boolean completed) {
        return new HandoffItemResult(
                HANDOFF_ITEM_ID,
                ROLE_ID,
                "질문 목록 문서 권한 넘기기",
                HandoffCategory.RESOURCE,
                completed
        );
    }

    private org.springframework.restdocs.snippet.Snippet workspacePathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("팀 UUID"),
                parameterWithName("seasonId").description("시즌 UUID")
        );
    }

    private org.springframework.restdocs.snippet.Snippet accessKeyHeader() {
        return requestHeaders(headerWithName("X-Baton-Access-Key").description("워크스페이스 접근 키"));
    }

    private FieldDescriptor[] workspaceResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("team.id").description("팀 UUID"),
                fieldWithPath("team.name").description("팀 이름"),
                fieldWithPath("season.id").description("시즌 UUID"),
                fieldWithPath("season.name").description("시즌 이름"),
                fieldWithPath("season.startDate").description("시즌 시작일"),
                fieldWithPath("season.endDate").description("시즌 종료일"),
                fieldWithPath("members[].id").description("구성원 UUID"),
                fieldWithPath("members[].name").description("구성원 이름"),
                fieldWithPath("members[].initials").description("표시용 이니셜"),
                fieldWithPath("members[].tone").description("표시용 색상"),
                fieldWithPath("roles[].id").description("역할 UUID"),
                fieldWithPath("roles[].name").description("역할 이름"),
                fieldWithPath("roles[].purpose").description("역할 목적"),
                fieldWithPath("roles[].currentMemberId").optional().description("현재 담당자 UUID"),
                fieldWithPath("roles[].nextMemberId").optional().description("다음 담당자 UUID"),
                fieldWithPath("roles[].assignmentStartDate").optional().description("배정 시작일"),
                fieldWithPath("roles[].assignmentEndDate").optional().description("배정 종료일"),
                fieldWithPath("roles[].responsibilities[]").description("역할 책임 목록"),
                fieldWithPath("roles[].risk").optional().description("위험 신호"),
                fieldWithPath("routines[].id").description("루틴 UUID"),
                fieldWithPath("routines[].title").description("루틴 제목"),
                fieldWithPath("routines[].phase").description("실행 단계"),
                fieldWithPath("routines[].dueLabel").description("기한 문구"),
                fieldWithPath("routines[].ownerRoleId").description("담당 역할 UUID"),
                fieldWithPath("routines[].status").description("WAITING 또는 DONE"),
                fieldWithPath("routines[].detail").description("루틴 상세"),
                fieldWithPath("decisions[].id").description("결정 UUID"),
                fieldWithPath("decisions[].title").description("결정 제목"),
                fieldWithPath("decisions[].reason").description("결정 이유"),
                fieldWithPath("decisions[].alternative").description("검토한 대안"),
                fieldWithPath("decisions[].createdAt").description("서버가 기록한 UTC 시각"),
                fieldWithPath("decisions[].authorName").description("작성자 이름"),
                fieldWithPath("decisions[].roleIds[]").description("관련 역할 UUID 목록"),
                fieldWithPath("handoffItems[].id").description("인수인계 항목 UUID"),
                fieldWithPath("handoffItems[].roleId").description("소유 역할 UUID"),
                fieldWithPath("handoffItems[].label").description("항목 내용"),
                fieldWithPath("handoffItems[].category").description("항목 분류"),
                fieldWithPath("handoffItems[].completed").description("완료 여부")
        };
    }

    private FieldDescriptor[] roleResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("id").description("역할 UUID"),
                fieldWithPath("name").description("역할 이름"),
                fieldWithPath("purpose").description("역할 목적"),
                fieldWithPath("currentMemberId").optional().description("현재 담당자 UUID"),
                fieldWithPath("nextMemberId").optional().description("다음 담당자 UUID"),
                fieldWithPath("assignmentStartDate").optional().description("배정 시작일"),
                fieldWithPath("assignmentEndDate").optional().description("배정 종료일"),
                fieldWithPath("responsibilities[]").description("역할 책임 목록"),
                fieldWithPath("risk").optional().description("위험 신호")
        };
    }

    private FieldDescriptor[] routineResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("id").description("루틴 UUID"),
                fieldWithPath("title").description("루틴 제목"),
                fieldWithPath("phase").description("실행 단계"),
                fieldWithPath("dueLabel").description("기한 문구"),
                fieldWithPath("ownerRoleId").description("담당 역할 UUID"),
                fieldWithPath("status").description("WAITING 또는 DONE"),
                fieldWithPath("detail").description("실행 방법")
        };
    }

    private FieldDescriptor[] decisionResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("id").description("결정 UUID"),
                fieldWithPath("title").description("결정 제목"),
                fieldWithPath("reason").description("결정 이유"),
                fieldWithPath("alternative").description("검토한 대안"),
                fieldWithPath("createdAt").description("서버가 기록한 UTC 시각"),
                fieldWithPath("authorName").description("작성자 이름"),
                fieldWithPath("roleIds[]").description("관련 역할 UUID 목록")
        };
    }

    private FieldDescriptor[] handoffItemResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("id").description("인수인계 항목 UUID"),
                fieldWithPath("roleId").description("소유 역할 UUID"),
                fieldWithPath("label").description("항목 내용"),
                fieldWithPath("category").description("항목 분류"),
                fieldWithPath("completed").description("완료 여부")
        };
    }

    private FieldDescriptor[] errorResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("code").description("안정적인 오류 코드"),
                fieldWithPath("message").description("사용자에게 표시할 오류 설명")
        };
    }
}
