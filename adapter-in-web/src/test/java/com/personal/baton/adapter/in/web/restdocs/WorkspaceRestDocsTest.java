package com.personal.baton.adapter.in.web.restdocs;

import com.epages.restdocs.apispec.ConstrainedFields;
import com.epages.restdocs.apispec.EnumFields;
import com.epages.restdocs.apispec.MockMvcRestDocumentationWrapper;
import com.personal.baton.adapter.in.web.GlobalExceptionHandler;
import com.personal.baton.adapter.in.web.RequestIdFilter;
import com.personal.baton.adapter.in.web.identity.BatonAccountPrincipal;
import com.personal.baton.adapter.in.web.workspace.WorkspaceController;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRoleController;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRoutineRoundController;
import com.personal.baton.adapter.in.web.workspace.WorkspaceSeasonController;
import com.personal.baton.adapter.in.web.workspace.WorkspaceRequests;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.workspace.error.IdempotencyKeyConflictException;
import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.error.IdempotencyReplayExpiredException;
import com.personal.baton.application.workspace.error.MemberNameConflictException;
import com.personal.baton.application.workspace.error.RoleNameConflictException;
import com.personal.baton.application.workspace.error.RoleHandoffStateConflictException;
import com.personal.baton.application.workspace.error.RoleHandoffWarningConfirmationRequiredException;
import com.personal.baton.application.workspace.error.SeasonEndedException;
import com.personal.baton.application.workspace.error.SeasonNameConflictException;
import com.personal.baton.application.workspace.error.SeasonRoundNameConflictException;
import com.personal.baton.application.workspace.error.SeasonSuccessorExistsException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceAccessKeyConflictException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.error.WorkspaceCreationDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.error.WorkspaceRecoveryDeniedException;
import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceQueryUseCase.ContinuitySignalResult;
import com.personal.baton.application.workspace.port.in.WorkspaceDecisionUseCase.CreateDecisionCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceHandoffItemUseCase.CreateHandoffItemCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceMemberUseCase.CreateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.CreateNextSeasonCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleUseCase.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleResourceUseCase.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoutineUseCase.CreateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceCreationUseCase.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceDecisionUseCase.DecisionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceHandoffItemUseCase.HandoffItemResult;
import com.personal.baton.application.workspace.port.in.WorkspaceMemberUseCase.MemberResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleUseCase.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleHandoffUseCase.RoleHandoffResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleHandoffUseCase.RoleHandoffTransitionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleResourceQueryUseCase.RoleResourceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.RoutineExecutionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRoutineUseCase.RoutineResult;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.SeasonRoundResult;
import com.personal.baton.application.workspace.port.in.WorkspaceMemberUseCase.UpdateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleUseCase.UpdateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleResourceUseCase.UpdateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRoutineUseCase.UpdateRoutineCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.UpdateRoundScheduleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonUseCase.UpdateSeasonCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceSeasonRoundUseCase.UpdateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceDecisionUseCase.UpdateDecisionCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceHandoffItemUseCase.UpdateHandoffItemCommand;
import com.personal.baton.domain.workspace.HandoffCategory;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.RoundOrigin;
import com.personal.baton.domain.workspace.RoleHandoffStatus;
import com.personal.baton.domain.workspace.RoundRecurrence;
import com.personal.baton.domain.workspace.RoundTimingStatus;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineStatus;
import com.personal.baton.domain.workspace.RoutineTimingStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.constraints.Constraint;
import org.springframework.restdocs.headers.HeaderDescriptor;
import org.springframework.restdocs.headers.ResponseHeadersSnippet;
import org.springframework.restdocs.payload.FieldDescriptor;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler;
import org.springframework.restdocs.snippet.Snippet;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.patch;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.snippet.Attributes.key;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@Tag("restdocs")
@ExtendWith(RestDocumentationExtension.class)
class WorkspaceRestDocsTest {

    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEASON_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NEXT_SEASON_ID =
            UUID.fromString("22222222-2222-2222-2222-333333333333");
    private static final UUID MEMBER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID NEXT_MEMBER_ID = UUID.fromString("33333333-3333-3333-3333-444444444444");
    private static final UUID CREATED_MEMBER_ID = UUID.fromString("33333333-3333-3333-3333-555555555555");
    private static final UUID ROLE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID COPIED_ROLE_ID =
            UUID.fromString("44444444-4444-4444-4444-555555555555");
    private static final UUID ROUTINE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID COPIED_ROUTINE_ID =
            UUID.fromString("55555555-5555-5555-5555-666666666666");
    private static final UUID ROUND_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID EXECUTION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID DECISION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID HANDOFF_ITEM_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID ROLE_HANDOFF_ID =
            UUID.fromString("77777777-7777-7777-7777-888888888888");
    private static final UUID ROLE_RESOURCE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final String ACCESS_KEY = "baton-access-key";
    private static final String NEW_ACCESS_KEY = "rotated-baton-access-key";
    private static final String IDEMPOTENCY_KEY = "workspace-idempotency-restdocs-0001";
    private static final String CONTENT_IDEMPOTENCY_KEY = "content-idempotency-restdocs-000001";
    private static final String ACCESS_KEY_CHANGE_IDEMPOTENCY_KEY = "access-key-change-restdocs-0000001";
    private static final String CREATION_KEY = "pilot-operator-key";
    private static final String RECOVERY_KEY = "pilot-recovery-key";
    private static final OperationDocumentation CREATE_WORKSPACE = new OperationDocumentation(
            "워크스페이스 생성",
            "팀, 첫 시즌과 구성원을 만들고 원문 접근 키를 한 번 반환한다."
    );
    private static final OperationDocumentation CREATE_OWNED_WORKSPACE =
            new OperationDocumentation(
                    "세션 OWNER 워크스페이스 생성",
                    "로그인 계정과 선택한 초기 구성원을 OWNER로 원자 결속하고 접근 키 없이 팀과 첫 시즌을 만든다."
            );
    private static final OperationDocumentation GET_WORKSPACE = new OperationDocumentation(
            "워크스페이스 조회",
            "Today 화면에 필요한 팀, 시즌, 역할, 역할 자료, 루틴 정의, 회차별 실행, 결정과 인수인계 projection을 조회한다."
    );
    private static final OperationDocumentation UPDATE_SEASON = new OperationDocumentation(
            "시즌 정보 수정",
            "종료되지 않은 시즌의 이름과 운영 기간을 기존 기록 경계 안에서 수정한다."
    );
    private static final OperationDocumentation UPDATE_ROUND_SCHEDULE = new OperationDocumentation(
            "자동 회차 일정 설정",
            "시즌 시간대와 주간 또는 격주 회차 생성을 설정하거나 일시중지한다."
    );
    private static final OperationDocumentation UPDATE_SEASON_ENDING = new OperationDocumentation(
            "시즌 종료 상태 변경",
            "시즌 기록을 지우지 않고 읽기 전용으로 종료하거나 후속 시즌이 없을 때 다시 연다."
    );
    private static final OperationDocumentation CREATE_NEXT_SEASON = new OperationDocumentation(
            "다음 시즌 시작",
            "현재 시즌을 종료하고 선택한 역할과 루틴 정의만 새 시즌 snapshot으로 이어 간다."
    );
    private static final OperationDocumentation CREATE_MEMBER = new OperationDocumentation(
            "구성원 추가",
            "현재 워크스페이스의 팀 구성원을 추가하고 역할 배정에서 사용할 표시 정보를 반환한다."
    );
    private static final OperationDocumentation UPDATE_MEMBER = new OperationDocumentation(
            "구성원 이름 수정",
            "구성원 식별자와 기존 기록을 유지하면서 현재 표시 이름을 수정한다."
    );
    private static final OperationDocumentation UPDATE_MEMBER_DEACTIVATION =
            new OperationDocumentation(
                    "구성원 활동 상태 변경",
                    "기존 역할과 결정 참조를 보존하면서 새 배정 가능 여부를 변경한다."
            );
    private static final OperationDocumentation ROTATE_ACCESS_KEY = new OperationDocumentation(
            "접근 키 회전",
            "현재 접근 키를 검증하고 새 워크스페이스 접근 키를 한 번 반환한다."
    );
    private static final OperationDocumentation RECOVER_ACCESS_KEY = new OperationDocumentation(
            "접근 키 복구",
            "운영자 복구 키를 검증하고 새 워크스페이스 접근 키를 한 번 반환한다."
    );
    private static final OperationDocumentation CREATE_ROLE = new OperationDocumentation(
            "역할 생성",
            "현재 시즌에 역할, 담당자, 책임과 위험 신호를 등록한다."
    );
    private static final OperationDocumentation UPDATE_ROLE = new OperationDocumentation(
            "역할 수정",
            "현재 시즌의 역할 이름, 담당자, 책임과 위험 신호를 수정한다."
    );
    private static final OperationDocumentation PREPARE_ROLE_HANDOFF = new OperationDocumentation(
            "역할 바통 준비",
            "이전·다음 담당자와 두 담당 기간을 고정해 역할 교대를 준비한다."
    );
    private static final OperationDocumentation TRANSFER_ROLE_HANDOFF = new OperationDocumentation(
            "역할 바통 전달",
            "이전 담당자 명의의 확인과 준비도 경고 확인을 기록해 바통을 수락 대기로 전환한다."
    );
    private static final OperationDocumentation ACCEPT_ROLE_HANDOFF = new OperationDocumentation(
            "역할 바통 수락",
            "다음 담당자 명의의 확인과 함께 역할 담당자와 담당 기간을 원자적으로 전환한다."
    );
    private static final OperationDocumentation CANCEL_ROLE_HANDOFF = new OperationDocumentation(
            "역할 바통 취소",
            "수락 전 역할 바통을 취소하고 역할의 다음 담당자 예약을 되돌린다."
    );
    private static final OperationDocumentation CREATE_ROUTINE = new OperationDocumentation(
            "루틴 생성",
            "현재 시즌에 반복해서 실행할 팀 루틴 정의를 등록한다."
    );
    private static final OperationDocumentation UPDATE_ROUTINE = new OperationDocumentation(
            "루틴 수정",
            "현재 시즌의 팀 루틴 정의를 수정한다."
    );
    private static final OperationDocumentation CREATE_SEASON_ROUND = new OperationDocumentation(
            "시즌 회차 생성",
            "시즌에 수동 회차를 만들고 현재 루틴 정의를 실행 항목으로 복제한다."
    );
    private static final OperationDocumentation UPDATE_SEASON_ROUND = new OperationDocumentation(
            "시즌 회차 수정",
            "활성 회차의 이름과 모임 날짜를 정정하고 기존 루틴 실행 스냅샷은 유지한다."
    );
    private static final OperationDocumentation UPDATE_SEASON_ROUND_ARCHIVE = new OperationDocumentation(
            "시즌 회차 보관 상태 변경",
            "회차와 루틴 실행 기록을 지우지 않고 활성 목록에서 보관하거나 다시 복원한다."
    );
    private static final OperationDocumentation UPDATE_ROUTINE_EXECUTION_COMPLETION = new OperationDocumentation(
            "회차 루틴 실행 완료 상태 변경",
            "특정 회차의 루틴 실행 완료 여부를 WAITING 또는 DONE 상태로 변경한다."
    );
    private static final OperationDocumentation CREATE_DECISION = new OperationDocumentation(
            "결정 생성",
            "결정과 이유, 검토한 대안, 작성자와 관련 역할을 기록한다."
    );
    private static final OperationDocumentation UPDATE_DECISION = new OperationDocumentation(
            "결정 수정",
            "결정의 내용, 이유, 대안, 작성자와 관련 역할을 정정한다."
    );
    private static final OperationDocumentation UPDATE_DECISION_ARCHIVE = new OperationDocumentation(
            "결정 보관 상태 변경",
            "결정 기록을 활성 목록에서 보관하거나 다시 복원한다."
    );
    private static final OperationDocumentation CREATE_HANDOFF_ITEM = new OperationDocumentation(
            "인수인계 항목 생성",
            "역할에 연결된 미완료 인수인계 항목을 등록한다."
    );
    private static final OperationDocumentation UPDATE_HANDOFF_ITEM = new OperationDocumentation(
            "인수인계 항목 수정",
            "인수인계 항목의 역할, 내용과 분류를 정정한다."
    );
    private static final OperationDocumentation UPDATE_HANDOFF_ITEM_COMPLETION = new OperationDocumentation(
            "인수인계 항목 완료 상태 변경",
            "인수인계 항목의 완료 여부를 변경한다."
    );
    private static final OperationDocumentation UPDATE_HANDOFF_ITEM_ARCHIVE = new OperationDocumentation(
            "인수인계 항목 보관 상태 변경",
            "인수인계 항목을 활성 체크리스트에서 보관하거나 다시 복원한다."
    );
    private static final OperationDocumentation CREATE_ROLE_RESOURCE = new OperationDocumentation(
            "역할 자료 생성",
            "역할 수행과 인수인계에 계속 사용할 외부 자료 링크를 등록한다."
    );
    private static final OperationDocumentation UPDATE_ROLE_RESOURCE = new OperationDocumentation(
            "역할 자료 수정",
            "역할에 연결된 외부 자료 링크의 제목, URL과 설명을 수정한다."
    );

    private WorkspaceUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        useCase = mock(WorkspaceUseCase.class);
        mockMvc = standaloneSetup(
                new WorkspaceController(useCase),
                new WorkspaceRoleController(useCase, useCase),
                new WorkspaceRoutineRoundController(useCase, useCase),
                new WorkspaceSeasonController(useCase)
        )
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter(() -> REQUEST_ID))
                .apply(documentationConfiguration(restDocumentation)
                        .operationPreprocessors()
                        .withRequestDefaults(prettyPrint())
                        .withResponseDefaults(prettyPrint()))
                .alwaysExpect(header().string(
                        RequestIdFilter.HEADER_NAME,
                        REQUEST_ID.toString()
                ))
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
                .andDo(document(
                        "createWorkspace",
                        CREATE_WORKSPACE,
                        requestHeaders(
                                headerWithName("Idempotency-Key")
                                        .description("32~200자의 URL 안전 멱등 키"),
                                headerWithName("X-Baton-Creation-Key").optional()
                                        .description("운영 환경에서 설정한 파일럿 생성 키")
                        ),
                        requestFields(
                                requestField(WorkspaceRequests.CreateWorkspaceRequest.class,
                                        "teamName", "팀 이름"),
                                requestField(WorkspaceRequests.CreateWorkspaceRequest.class,
                                        "seasonName", "첫 시즌 이름"),
                                requestField(WorkspaceRequests.CreateWorkspaceRequest.class,
                                        "startDate", "시즌 시작일(ISO-8601 날짜)"),
                                requestField(WorkspaceRequests.CreateWorkspaceRequest.class,
                                        "endDate", "시즌 종료일(ISO-8601 날짜)"),
                                requestStringArrayField(WorkspaceRequests.CreateWorkspaceRequest.class,
                                        "memberNames", "memberNames[]", "한 명 이상의 구성원 이름")
                        ),
                        responseHeadersWithRequestId(
                                headerWithName("Location")
                                        .description("생성한 워크스페이스 조회 URI"),
                                headerWithName("Cache-Control")
                                        .description("원문 접근 키 응답을 저장하지 않도록 하는 no-store 지시자")
                        ),
                        responseFields(
                                fieldWithPath("teamId").description("생성한 팀 UUID"),
                                fieldWithPath("seasonId").description("생성한 시즌 UUID"),
                                fieldWithPath("accessKey").description("이 응답에서만 제공하는 원문 접근 키")
                        )));
    }

    @DisplayName("세션 OWNER 워크스페이스 생성 API는 접근 키 없이 팀과 시즌 식별자만 반환한다")
    @Test
    void documentsCreateOwnedWorkspace() throws Exception {
        AuthenticatedAccount account = new AuthenticatedAccount(ACCOUNT_ID);
        when(useCase.createWorkspaceForOwner(
                eq(IDEMPOTENCY_KEY),
                eq(account),
                eq("박민서"),
                any(CreateWorkspaceCommand.class)
        )).thenReturn(new WorkspaceUseCase.CreatedWorkspaceResult(TEAM_ID, SEASON_ID, null));
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                new BatonAccountPrincipal(ACCOUNT_ID),
                null,
                List.of()
        );

        mockMvc.perform(post("/api/v1/me/workspaces")
                        .principal(authentication)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-CSRF-TOKEN", "session-bound-csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "teamName": "알고리즘 한 바퀴",
                                  "seasonName": "2026 여름 시즌",
                                  "startDate": "2026-07-02",
                                  "endDate": "2026-09-17",
                                  "memberNames": ["박민서", "김준호"],
                                  "ownerMemberName": "박민서"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "/api/v1/teams/" + TEAM_ID + "/seasons/" + SEASON_ID + "/workspace"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.teamId").value(TEAM_ID.toString()))
                .andExpect(jsonPath("$.seasonId").value(SEASON_ID.toString()))
                .andExpect(jsonPath("$.accessKey").doesNotExist())
                .andDo(document(
                        "createOwnedWorkspace",
                        CREATE_OWNED_WORKSPACE,
                        requestHeaders(
                                headerWithName("Idempotency-Key")
                                        .description("32~200자의 URL 안전 멱등 키"),
                                headerWithName("X-CSRF-TOKEN")
                                        .description("현재 로그인 세션의 동적 CSRF 토큰")
                        ),
                        requestFields(
                                requestField(WorkspaceRequests.CreateOwnedWorkspaceRequest.class,
                                        "teamName", "팀 이름"),
                                requestField(WorkspaceRequests.CreateOwnedWorkspaceRequest.class,
                                        "seasonName", "첫 시즌 이름"),
                                requestField(WorkspaceRequests.CreateOwnedWorkspaceRequest.class,
                                        "startDate", "시즌 시작일(ISO-8601 날짜)"),
                                requestField(WorkspaceRequests.CreateOwnedWorkspaceRequest.class,
                                        "endDate", "시즌 종료일(ISO-8601 날짜)"),
                                requestStringArrayField(
                                        WorkspaceRequests.CreateOwnedWorkspaceRequest.class,
                                        "memberNames",
                                        "memberNames[]",
                                        "한 명 이상의 구성원 이름"
                                ),
                                requestField(WorkspaceRequests.CreateOwnedWorkspaceRequest.class,
                                        "ownerMemberName", "OWNER로 결속할 초기 구성원 이름")
                        ),
                        responseHeadersWithRequestId(
                                headerWithName("Location")
                                        .description("생성한 워크스페이스 조회 URI"),
                                headerWithName("Cache-Control")
                                        .description("세션 생성 응답을 저장하지 않는 no-store 지시자")
                        ),
                        responseFields(
                                fieldWithPath("teamId").description("생성한 팀 UUID"),
                                fieldWithPath("seasonId").description("생성한 시즌 UUID")
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
                .andExpect(jsonPath("$.routines[0].status").doesNotExist())
                .andExpect(jsonPath("$.rounds[0].meetingDate").value("2026-07-27"))
                .andExpect(jsonPath("$.rounds[0].routineExecutions[0].status").value("WAITING"))
                .andExpect(jsonPath("$.decisions[0].createdAt").value("2026-07-20T03:04:05Z"))
                .andExpect(jsonPath("$.handoffItems[0].category").value("RESOURCE"))
                .andExpect(jsonPath("$.handoffItems[0].createdAt").value(nullValue()))
                .andExpect(jsonPath("$.resources[0].url").value("https://docs.example.com/question-guide"))
                .andExpect(jsonPath("$.resources[0].createdAt").value(nullValue()))
                .andExpect(jsonPath("$.continuitySignals[0].type")
                        .value("HANDOFF_INCOMPLETE"))
                .andExpect(jsonPath("$.continuitySignals[0].recommendedAction")
                        .value("다음 담당자가 바통을 수락하고 남은 항목을 확인하세요."))
                .andDo(document(
                        "getWorkspace",
                        GET_WORKSPACE,
                        workspacePathParameters(),
                        accessKeyHeader(),
                        noStoreResponseHeader(),
                        responseFields(workspaceResponseFields())));
    }

    @DisplayName("시즌 정보 수정 API는 기록 범위를 지키며 이름과 기간을 바꾼다")
    @Test
    void documentsUpdateSeason() throws Exception {
        when(useCase.updateSeason(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ACCESS_KEY),
                any(UpdateSeasonCommand.class)
        )).thenReturn(seasonResult());

        mockMvc.perform(put("/api/v1/teams/{teamId}/seasons/{seasonId}", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateSeasonRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("2026 여름 시즌"))
                .andExpect(jsonPath("$.endedAt").value(nullValue()))
                .andDo(document(
                        "updateSeason",
                        UPDATE_SEASON,
                        workspacePathParameters(),
                        accessKeyHeader(),
                        requestFields(
                                requestField(WorkspaceRequests.UpdateSeasonRequest.class,
                                        "name", "팀 안에서 유일한 시즌 이름"),
                                requestField(WorkspaceRequests.UpdateSeasonRequest.class,
                                        "startDate", "시즌 시작일(ISO-8601 날짜)"),
                                requestField(WorkspaceRequests.UpdateSeasonRequest.class,
                                        "endDate", "시즌 종료일(ISO-8601 날짜)")
                        ),
                        responseFields(seasonResponseFields())));
    }

    @DisplayName("자동 회차 일정 API는 시즌 시간대와 주간 반복 설정을 반환한다")
    @Test
    void documentsUpdateRoundSchedule() throws Exception {
        when(useCase.updateRoundSchedule(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ACCESS_KEY),
                any(UpdateRoundScheduleCommand.class)
        )).thenReturn(seasonResult());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/round-schedule",
                        TEAM_ID,
                        SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRoundScheduleRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.roundSchedule.recurrence").value("WEEKLY"))
                .andExpect(jsonPath("$.roundSchedule.nextOccurrenceDate").value("2026-08-06"))
                .andDo(document(
                        "updateRoundSchedule",
                        UPDATE_ROUND_SCHEDULE,
                        workspacePathParameters(),
                        accessKeyHeader(),
                        requestFields(
                                requestField(WorkspaceRequests.UpdateRoundScheduleRequest.class,
                                        "timeZone", "IANA 시간대 식별자"),
                                requestField(WorkspaceRequests.UpdateRoundScheduleRequest.class,
                                        "firstMeetingDate", "첫 자동 회차 모임 날짜"),
                                requestField(WorkspaceRequests.UpdateRoundScheduleRequest.class,
                                        "meetingTime", "시즌 시간대 기준 모임 시각"),
                                requestEnumField(WorkspaceRequests.UpdateRoundScheduleRequest.class,
                                        RoundRecurrence.class,
                                        "recurrence",
                                        "반복 주기: WEEKLY 또는 BIWEEKLY"),
                                requestField(WorkspaceRequests.UpdateRoundScheduleRequest.class,
                                        "generationLeadDays", "회차를 미리 만들 기간(0~30일)"),
                                requestField(WorkspaceRequests.UpdateRoundScheduleRequest.class,
                                        "enabled", "자동 회차 생성 활성 여부")
                        ),
                        responseFields(seasonResponseFields())));
    }

    @DisplayName("자동 회차 일정 API는 입력, 접근, 소속과 시즌 상태 오류를 구분한다")
    @Test
    void documentsUpdateRoundScheduleErrors() throws Exception {
        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/round-schedule",
                        TEAM_ID,
                        SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "timeZone": " ",
                                  "firstMeetingDate": "2026-08-06",
                                  "meetingTime": "20:00",
                                  "recurrence": "WEEKLY",
                                  "generationLeadDays": 31,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andDo(document(
                        "updateRoundScheduleInvalidInput",
                        UPDATE_ROUND_SCHEDULE,
                        workspacePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        when(useCase.updateRoundSchedule(
                eq(TEAM_ID),
                eq(SEASON_ID),
                isNull(),
                any(UpdateRoundScheduleCommand.class)
        )).thenThrow(new WorkspaceAccessDeniedException());
        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/round-schedule",
                        TEAM_ID,
                        SEASON_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRoundScheduleRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"))
                .andDo(document(
                        "updateRoundScheduleAccessDenied",
                        UPDATE_ROUND_SCHEDULE,
                        workspacePathParameters(),
                        responseFields(errorResponseFields())));

        when(useCase.updateRoundSchedule(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ACCESS_KEY),
                any(UpdateRoundScheduleCommand.class)
        )).thenThrow(new WorkspaceNotFoundException("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/round-schedule",
                        TEAM_ID,
                        SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRoundScheduleRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEASON_NOT_FOUND"))
                .andDo(document(
                        "updateRoundScheduleNotFound",
                        UPDATE_ROUND_SCHEDULE,
                        workspacePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        when(useCase.updateRoundSchedule(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ACCESS_KEY),
                any(UpdateRoundScheduleCommand.class)
        )).thenThrow(new SeasonEndedException());
        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/round-schedule",
                        TEAM_ID,
                        SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRoundScheduleRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEASON_ENDED"))
                .andDo(document(
                        "updateRoundScheduleSeasonEnded",
                        UPDATE_ROUND_SCHEDULE,
                        workspacePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("시즌 정보 수정 API는 입력, 접근, 소속과 상태 충돌을 구분한다")
    @Test
    void documentsUpdateSeasonErrors() throws Exception {
        mockMvc.perform(put("/api/v1/teams/{teamId}/seasons/{seasonId}", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": " ",
                                  "startDate": "2026-07-02",
                                  "endDate": "2026-09-17"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andDo(document(
                        "updateSeasonInvalidInput",
                        UPDATE_SEASON,
                        workspacePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        when(useCase.updateSeason(
                eq(TEAM_ID),
                eq(SEASON_ID),
                isNull(),
                any(UpdateSeasonCommand.class)
        )).thenThrow(new WorkspaceAccessDeniedException());
        mockMvc.perform(put("/api/v1/teams/{teamId}/seasons/{seasonId}", TEAM_ID, SEASON_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateSeasonRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"))
                .andDo(document(
                        "updateSeasonAccessDenied",
                        UPDATE_SEASON,
                        workspacePathParameters(),
                        responseFields(errorResponseFields())));

        when(useCase.updateSeason(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ACCESS_KEY),
                any(UpdateSeasonCommand.class)
        )).thenThrow(new WorkspaceNotFoundException("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        mockMvc.perform(put("/api/v1/teams/{teamId}/seasons/{seasonId}", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateSeasonRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEASON_NOT_FOUND"))
                .andDo(document(
                        "updateSeasonNotFound",
                        UPDATE_SEASON,
                        workspacePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        when(useCase.updateSeason(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ACCESS_KEY),
                any(UpdateSeasonCommand.class)
        )).thenThrow(new SeasonNameConflictException());
        mockMvc.perform(put("/api/v1/teams/{teamId}/seasons/{seasonId}", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateSeasonRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEASON_NAME_CONFLICT"))
                .andDo(document(
                        "updateSeasonNameConflict",
                        UPDATE_SEASON,
                        workspacePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("시즌 종료 상태 변경 API는 최초 종료 시각을 반환한다")
    @Test
    void documentsUpdateSeasonEnding() throws Exception {
        when(useCase.updateSeasonEnding(TEAM_ID, SEASON_ID, ACCESS_KEY, true))
                .thenReturn(endedSeasonResult());

        mockMvc.perform(patch("/api/v1/teams/{teamId}/seasons/{seasonId}/ending", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ended\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endedAt").value("2026-09-18T00:00:00Z"))
                .andDo(document(
                        "updateSeasonEnding",
                        UPDATE_SEASON_ENDING,
                        workspacePathParameters(),
                        accessKeyHeader(),
                        requestFields(requestField(
                                WorkspaceRequests.UpdateSeasonEndingRequest.class,
                                "ended",
                                "true이면 종료하고 false이면 가능한 경우 다시 연다"
                        )),
                        responseFields(seasonResponseFields())));
    }

    @DisplayName("시즌 종료 상태 변경 API는 입력, 접근, 소속과 후속 시즌 충돌을 구분한다")
    @Test
    void documentsUpdateSeasonEndingErrors() throws Exception {
        mockMvc.perform(patch("/api/v1/teams/{teamId}/seasons/{seasonId}/ending", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ended\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andDo(document(
                        "updateSeasonEndingInvalidInput",
                        UPDATE_SEASON_ENDING,
                        workspacePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        when(useCase.updateSeasonEnding(TEAM_ID, SEASON_ID, null, true))
                .thenThrow(new WorkspaceAccessDeniedException());
        mockMvc.perform(patch("/api/v1/teams/{teamId}/seasons/{seasonId}/ending", TEAM_ID, SEASON_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ended\": true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"))
                .andDo(document(
                        "updateSeasonEndingAccessDenied",
                        UPDATE_SEASON_ENDING,
                        workspacePathParameters(),
                        responseFields(errorResponseFields())));

        when(useCase.updateSeasonEnding(TEAM_ID, SEASON_ID, ACCESS_KEY, true))
                .thenThrow(new WorkspaceNotFoundException("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        mockMvc.perform(patch("/api/v1/teams/{teamId}/seasons/{seasonId}/ending", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ended\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEASON_NOT_FOUND"))
                .andDo(document(
                        "updateSeasonEndingNotFound",
                        UPDATE_SEASON_ENDING,
                        workspacePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        when(useCase.updateSeasonEnding(TEAM_ID, SEASON_ID, ACCESS_KEY, false))
                .thenThrow(new SeasonSuccessorExistsException());
        mockMvc.perform(patch("/api/v1/teams/{teamId}/seasons/{seasonId}/ending", TEAM_ID, SEASON_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ended\": false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEASON_SUCCESSOR_EXISTS"))
                .andDo(document(
                        "updateSeasonEndingSuccessorExists",
                        UPDATE_SEASON_ENDING,
                        workspacePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("다음 시즌 시작 API는 선택한 역할과 루틴의 새 식별자 대응을 반환한다")
    @Test
    void documentsCreateNextSeason() throws Exception {
        when(useCase.createNextSeason(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateNextSeasonCommand.class)
        )).thenReturn(nextSeasonResult());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/successor", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateNextSeasonRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/v1/teams/" + TEAM_ID
                                + "/seasons/" + NEXT_SEASON_ID + "/workspace"
                ))
                .andExpect(jsonPath("$.sourceSeason.endedAt").value("2026-09-18T00:00:00Z"))
                .andExpect(jsonPath("$.season.previousSeasonId").value(SEASON_ID.toString()))
                .andExpect(jsonPath("$.copiedRoles[0].roleId").value(COPIED_ROLE_ID.toString()))
                .andExpect(jsonPath("$.copiedRoutines[0].routineId").value(COPIED_ROUTINE_ID.toString()))
                .andDo(document(
                        "createNextSeason",
                        CREATE_NEXT_SEASON,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        requestFields(
                                requestField(WorkspaceRequests.CreateNextSeasonRequest.class,
                                        "name", "팀 안에서 유일한 다음 시즌 이름"),
                                requestField(WorkspaceRequests.CreateNextSeasonRequest.class,
                                        "startDate", "원본 시즌 종료일보다 늦은 시작일"),
                                requestField(WorkspaceRequests.CreateNextSeasonRequest.class,
                                        "endDate", "다음 시즌 종료일"),
                                requestStringArrayField(
                                        WorkspaceRequests.CreateNextSeasonRequest.class,
                                        "copyRoleIds",
                                        "copyRoleIds[]",
                                        "다음 시즌으로 이어 갈 원본 역할 UUID 집합"
                                ),
                                requestStringArrayField(
                                        WorkspaceRequests.CreateNextSeasonRequest.class,
                                        "copyRoutineIds",
                                        "copyRoutineIds[]",
                                        "다음 시즌으로 이어 갈 원본 루틴 UUID 집합"
                                )
                        ),
                        responseHeadersWithRequestId(
                                headerWithName("Location").description("생성한 다음 시즌 workspace URI")
                        ),
                        responseFields(nextSeasonResponseFields())));
    }

    @DisplayName("다음 시즌 시작 API는 입력, 접근, 원본 소속과 중복 후속 시즌을 구분한다")
    @Test
    void documentsCreateNextSeasonErrors() throws Exception {
        when(useCase.createNextSeason(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateNextSeasonCommand.class)
        )).thenThrow(new DomainValidationException("다음 시즌 시작일은 현재 시즌 종료일보다 늦어야 합니다"));
        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/successor", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateNextSeasonRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andDo(document(
                        "createNextSeasonInvalidInput",
                        CREATE_NEXT_SEASON,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        responseFields(errorResponseFields())));

        when(useCase.createNextSeason(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                isNull(),
                any(CreateNextSeasonCommand.class)
        )).thenThrow(new WorkspaceAccessDeniedException());
        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/successor", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateNextSeasonRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"))
                .andDo(document(
                        "createNextSeasonAccessDenied",
                        CREATE_NEXT_SEASON,
                        workspacePathParameters(),
                        requestHeaders(headerWithName("Idempotency-Key")
                                .description("같은 생성 요청을 안전하게 재시도할 멱등 키")),
                        responseFields(errorResponseFields())));

        when(useCase.createNextSeason(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateNextSeasonCommand.class)
        )).thenThrow(new WorkspaceNotFoundException("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다"));
        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/successor", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateNextSeasonRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"))
                .andDo(document(
                        "createNextSeasonRoleNotFound",
                        CREATE_NEXT_SEASON,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        responseFields(errorResponseFields())));

        when(useCase.createNextSeason(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateNextSeasonCommand.class)
        )).thenThrow(new SeasonSuccessorExistsException());
        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/successor", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateNextSeasonRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEASON_SUCCESSOR_EXISTS"))
                .andDo(document(
                        "createNextSeasonSuccessorExists",
                        CREATE_NEXT_SEASON,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("종료된 시즌의 콘텐츠 변경은 409 읽기 전용 오류를 반환한다")
    @Test
    void documentsSeasonEndedMutation() throws Exception {
        when(useCase.createRole(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateRoleCommand.class)
        )).thenThrow(new SeasonEndedException());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/roles", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateRoleRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEASON_ENDED"))
                .andDo(document(
                        "createRoleSeasonEnded",
                        CREATE_ROLE,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("구성원 추가 API는 팀 구성원을 저장하고 표시 정보를 반환한다")
    @Test
    void documentsCreateMember() throws Exception {
        when(useCase.createMember(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateMemberCommand.class)
        )).thenReturn(new MemberResult(CREATED_MEMBER_ID, "최유진", "최", "#C8D6E5", null));

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/members", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validMemberRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CREATED_MEMBER_ID.toString()))
                .andExpect(jsonPath("$.name").value("최유진"))
                .andExpect(jsonPath("$.initials").value("최"))
                .andExpect(jsonPath("$.tone").value("#C8D6E5"))
                .andExpect(jsonPath("$.deactivatedAt").value(nullValue()))
                .andDo(document(
                        "createMember",
                        CREATE_MEMBER,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        requestFields(requestField(
                                WorkspaceRequests.CreateMemberRequest.class,
                                "name",
                                "팀 안에서 유일한 구성원 이름"
                        )),
                        responseFields(memberResponseFields())));
    }

    @DisplayName("빈 구성원 이름은 400 입력 오류 계약을 반환한다")
    @Test
    void documentsCreateMemberInvalidInput() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/members", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andDo(document(
                        "createMemberInvalidInput",
                        CREATE_MEMBER,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("접근 키가 틀리면 구성원 추가 API는 403 오류 계약을 반환한다")
    @Test
    void documentsCreateMemberAccessDenied() throws Exception {
        when(useCase.createMember(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq("wrong-key"),
                any(CreateMemberCommand.class)
        )).thenThrow(new WorkspaceAccessDeniedException());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/members", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validMemberRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"))
                .andDo(document(
                        "createMemberAccessDenied",
                        CREATE_MEMBER,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("구성원을 추가할 시즌이 없으면 404 오류 계약을 반환한다")
    @Test
    void documentsCreateMemberScopeNotFound() throws Exception {
        when(useCase.createMember(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateMemberCommand.class)
        )).thenThrow(new WorkspaceNotFoundException("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/members", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validMemberRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEASON_NOT_FOUND"))
                .andDo(document(
                        "createMemberScopeNotFound",
                        CREATE_MEMBER,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("같은 팀에 구성원 이름이 중복되면 409 오류 계약을 반환한다")
    @Test
    void documentsCreateMemberNameConflict() throws Exception {
        when(useCase.createMember(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateMemberCommand.class)
        )).thenThrow(new MemberNameConflictException());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/members", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validMemberRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_NAME_CONFLICT"))
                .andDo(document(
                        "createMemberNameConflict",
                        CREATE_MEMBER,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("구성원 이름 수정 API는 식별자와 활동 상태를 유지한 현재 표시 정보를 반환한다")
    @Test
    void documentsUpdateMember() throws Exception {
        when(useCase.updateMember(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(MEMBER_ID),
                eq(ACCESS_KEY),
                any(UpdateMemberCommand.class)
        )).thenReturn(new MemberResult(MEMBER_ID, "박민서(리드)", "박", "#d9e4da", null));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}",
                        TEAM_ID,
                        SEASON_ID,
                        MEMBER_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"박민서(리드)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$.name").value("박민서(리드)"))
                .andExpect(jsonPath("$.initials").value("박"))
                .andExpect(jsonPath("$.deactivatedAt").value(nullValue()))
                .andDo(document(
                        "updateMember",
                        UPDATE_MEMBER,
                        memberPathParameters(),
                        accessKeyHeader(),
                        requestFields(requestField(
                                WorkspaceRequests.UpdateMemberRequest.class,
                                "name",
                                "팀 안에서 유일한 새 표시 이름"
                        )),
                        responseFields(memberResponseFields())));
    }

    @DisplayName("수정할 구성원이 없으면 구성원 이름 수정 API는 식별 가능한 404 오류를 반환한다")
    @Test
    void documentsUpdateMemberNotFound() throws Exception {
        when(useCase.updateMember(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(MEMBER_ID),
                eq(ACCESS_KEY),
                any(UpdateMemberCommand.class)
        )).thenThrow(new WorkspaceNotFoundException(
                "MEMBER_NOT_FOUND",
                "구성원을 찾을 수 없습니다"
        ));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}",
                        TEAM_ID,
                        SEASON_ID,
                        MEMBER_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"박민서(리드)\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"))
                .andDo(document(
                        "updateMemberNotFound",
                        UPDATE_MEMBER,
                        memberPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("구성원 이름 수정이 기존 이름과 겹치면 409 오류 계약을 반환한다")
    @Test
    void documentsUpdateMemberNameConflict() throws Exception {
        when(useCase.updateMember(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(MEMBER_ID),
                eq(ACCESS_KEY),
                any(UpdateMemberCommand.class)
        )).thenThrow(new MemberNameConflictException());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}",
                        TEAM_ID,
                        SEASON_ID,
                        MEMBER_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"김준호\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_NAME_CONFLICT"))
                .andDo(document(
                        "updateMemberNameConflict",
                        UPDATE_MEMBER,
                        memberPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("구성원 이름 수정이 다른 변경과 충돌하면 409 오류 계약을 반환한다")
    @Test
    void documentsUpdateMemberContentConflict() throws Exception {
        when(useCase.updateMember(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(MEMBER_ID),
                eq(ACCESS_KEY),
                any(UpdateMemberCommand.class)
        )).thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}",
                        TEAM_ID,
                        SEASON_ID,
                        MEMBER_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"박민서(진행)\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"))
                .andDo(document(
                        "updateMemberContentConflict",
                        UPDATE_MEMBER,
                        memberPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("구성원 활동 상태 변경 API는 기존 참조를 유지할 비활성 시각을 반환한다")
    @Test
    void documentsUpdateMemberDeactivation() throws Exception {
        Instant deactivatedAt = Instant.parse("2026-07-29T03:04:05Z");
        when(useCase.updateMemberDeactivation(
                TEAM_ID,
                SEASON_ID,
                MEMBER_ID,
                ACCESS_KEY,
                true
        )).thenReturn(new MemberResult(
                MEMBER_ID,
                "박민서",
                "박",
                "#d9e4da",
                deactivatedAt
        ));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}/deactivation",
                        TEAM_ID,
                        SEASON_ID,
                        MEMBER_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deactivated\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$.deactivatedAt").value(deactivatedAt.toString()))
                .andDo(document(
                        "updateMemberDeactivation",
                        UPDATE_MEMBER_DEACTIVATION,
                        memberPathParameters(),
                        accessKeyHeader(),
                        requestFields(requestField(
                                WorkspaceRequests.MemberDeactivationRequest.class,
                                "deactivated",
                                "true면 활동 종료, false면 다시 활성화"
                        )),
                        responseFields(memberResponseFields())));
    }

    @DisplayName("활동 상태를 바꿀 구성원이 없으면 식별 가능한 404 오류를 반환한다")
    @Test
    void documentsUpdateMemberDeactivationNotFound() throws Exception {
        when(useCase.updateMemberDeactivation(
                TEAM_ID,
                SEASON_ID,
                MEMBER_ID,
                ACCESS_KEY,
                true
        )).thenThrow(new WorkspaceNotFoundException(
                "MEMBER_NOT_FOUND",
                "구성원을 찾을 수 없습니다"
        ));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}/deactivation",
                        TEAM_ID,
                        SEASON_ID,
                        MEMBER_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deactivated\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"))
                .andDo(document(
                        "updateMemberDeactivationNotFound",
                        UPDATE_MEMBER_DEACTIVATION,
                        memberPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("구성원 활동 상태 변경이 다른 변경과 충돌하면 409 오류를 반환한다")
    @Test
    void documentsUpdateMemberDeactivationConflict() throws Exception {
        when(useCase.updateMemberDeactivation(
                TEAM_ID,
                SEASON_ID,
                MEMBER_ID,
                ACCESS_KEY,
                true
        )).thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}/deactivation",
                        TEAM_ID,
                        SEASON_ID,
                        MEMBER_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deactivated\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"))
                .andDo(document(
                        "updateMemberDeactivationConflict",
                        UPDATE_MEMBER_DEACTIVATION,
                        memberPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
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
                .andDo(document(
                        "rotateAccessKey",
                        ROTATE_ACCESS_KEY,
                        workspacePathParameters(),
                        requestHeaders(
                                headerWithName("Idempotency-Key")
                                        .description("접근 키 회전 응답을 재생할 32~200자의 멱등 키"),
                                headerWithName("X-Baton-Access-Key")
                                        .description("현재 워크스페이스 접근 키")
                        ),
                        noStoreResponseHeader(),
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
                .andDo(document(
                        "rotateAccessKeyConflict",
                        ROTATE_ACCESS_KEY,
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
                .andDo(document(
                        "recoverAccessKey",
                        RECOVER_ACCESS_KEY,
                        workspacePathParameters(),
                        requestHeaders(
                                headerWithName("Idempotency-Key")
                                        .description("접근 키 복구 응답을 재생할 32~200자의 멱등 키"),
                                headerWithName("X-Baton-Recovery-Key")
                                        .description("설정된 파일럿 운영자 복구 키")
                        ),
                        noStoreResponseHeader(),
                        responseFields(fieldWithPath("accessKey")
                                .description("복구 시 한 번만 제공하는 새 워크스페이스 접근 키"))));
    }

    @DisplayName("역할 생성 API는 팀 역할과 책임 목록을 저장해 반환한다")
    @Test
    void documentsCreateRole() throws Exception {
        when(useCase.createRole(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateRoleCommand.class)
        ))
                .thenReturn(roleResult());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/roles", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
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
                .andDo(document(
                        "createRole",
                        CREATE_ROLE,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        requestFields(
                                requestField(WorkspaceRequests.CreateRoleRequest.class,
                                        "name", "팀에서 유일한 역할 이름"),
                                requestField(WorkspaceRequests.CreateRoleRequest.class,
                                        "purpose", "역할의 목적"),
                                optionalRequestField(WorkspaceRequests.CreateRoleRequest.class,
                                        "currentMemberId", "현재 담당 구성원 UUID"),
                                optionalRequestField(WorkspaceRequests.CreateRoleRequest.class,
                                        "nextMemberId", "다음 담당 구성원 UUID"),
                                optionalRequestField(WorkspaceRequests.CreateRoleRequest.class,
                                        "assignmentStartDate", "배정 시작일"),
                                optionalRequestField(WorkspaceRequests.CreateRoleRequest.class,
                                        "assignmentEndDate", "배정 종료일"),
                                requestStringArrayField(WorkspaceRequests.CreateRoleRequest.class,
                                        "responsibilities", "responsibilities[]", "역할 책임 목록"),
                                optionalRequestField(WorkspaceRequests.CreateRoleRequest.class,
                                        "risk", "인수인계 위험 신호")
                        ),
                        responseFields(roleResponseFields())));
    }

    @DisplayName("역할 수정 API는 역할의 담당자와 책임을 바꿔 반환한다")
    @Test
    void documentsUpdateRole() throws Exception {
        when(useCase.updateRole(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(ACCESS_KEY),
                any(UpdateRoleCommand.class)
        ))
                .thenReturn(updatedRoleResult());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateRoleRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ROLE_ID.toString()))
                .andExpect(jsonPath("$.name").value("회고 큐레이터"))
                .andExpect(jsonPath("$.currentMemberId").value(NEXT_MEMBER_ID.toString()))
                .andExpect(jsonPath("$.responsibilities.length()").value(2))
                .andDo(document(
                        "updateRole",
                        UPDATE_ROLE,
                        rolePathParameters(),
                        accessKeyHeader(),
                        requestFields(
                                requestField(WorkspaceRequests.UpdateRoleRequest.class,
                                        "name", "팀에서 유일한 역할 이름"),
                                requestField(WorkspaceRequests.UpdateRoleRequest.class,
                                        "purpose", "역할의 목적"),
                                optionalRequestField(WorkspaceRequests.UpdateRoleRequest.class,
                                        "currentMemberId", "현재 담당 구성원 UUID"),
                                optionalRequestField(WorkspaceRequests.UpdateRoleRequest.class,
                                        "nextMemberId", "다음 담당 구성원 UUID"),
                                optionalRequestField(WorkspaceRequests.UpdateRoleRequest.class,
                                        "assignmentStartDate", "배정 시작일"),
                                optionalRequestField(WorkspaceRequests.UpdateRoleRequest.class,
                                        "assignmentEndDate", "배정 종료일"),
                                requestStringArrayField(WorkspaceRequests.UpdateRoleRequest.class,
                                        "responsibilities", "responsibilities[]", "역할 책임 목록"),
                                optionalRequestField(WorkspaceRequests.UpdateRoleRequest.class,
                                        "risk", "인수인계 위험 신호")
                        ),
                        responseFields(roleResponseFields())));
    }

    @DisplayName("역할 바통 준비 API는 다음 담당 기간을 고정하고 고유한 바통을 만든다")
    @Test
    void documentsPrepareRoleHandoff() throws Exception {
        when(useCase.prepareRoleHandoff(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(WorkspaceUseCase.PrepareRoleHandoffCommand.class)
        )).thenReturn(roleHandoffTransitionResult(RoleHandoffStatus.PREPARING));

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toMemberId": "33333333-3333-3333-3333-444444444444",
                                  "incomingAssignmentStartDate": "2026-08-01",
                                  "incomingAssignmentEndDate": "2026-09-17"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "/api/v1/teams/" + TEAM_ID
                                + "/seasons/" + SEASON_ID
                                + "/roles/" + ROLE_ID
                                + "/handoffs/" + ROLE_HANDOFF_ID
                ))
                .andExpect(jsonPath("$.handoff.status").value("PREPARING"))
                .andExpect(jsonPath("$.role.nextMemberId").value(NEXT_MEMBER_ID.toString()))
                .andDo(document(
                        "prepareRoleHandoff",
                        PREPARE_ROLE_HANDOFF,
                        rolePathParameters(),
                        contentCreationHeaders(),
                        requestFields(
                                requestField(
                                        WorkspaceRequests.PrepareRoleHandoffRequest.class,
                                        "toMemberId",
                                        "다음 담당 구성원 UUID"
                                ),
                                requestField(
                                        WorkspaceRequests.PrepareRoleHandoffRequest.class,
                                        "incomingAssignmentStartDate",
                                        "수락 뒤 적용할 다음 담당 시작일"
                                ),
                                optionalRequestField(
                                        WorkspaceRequests.PrepareRoleHandoffRequest.class,
                                        "incomingAssignmentEndDate",
                                        "수락 뒤 적용할 다음 담당 종료일"
                                )
                        ),
                        responseHeadersWithRequestId(
                                headerWithName("Location").description("준비한 역할 바통 URI")
                        ),
                        responseFields(roleHandoffTransitionResponseFields())));
    }

    @DisplayName("역할 바통 전달 API는 이전 담당자의 확인과 준비도 스냅샷을 기록한다")
    @Test
    void documentsTransferRoleHandoff() throws Exception {
        when(useCase.transferRoleHandoff(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(ROLE_HANDOFF_ID),
                eq(ACCESS_KEY),
                any(WorkspaceUseCase.TransferRoleHandoffCommand.class)
        )).thenReturn(roleHandoffTransitionResult(RoleHandoffStatus.TRANSFERRED));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}"
                                + "/handoffs/{handoffId}/transfer",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID,
                        ROLE_HANDOFF_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "confirmedByMemberId": "33333333-3333-3333-3333-333333333333",
                                  "warningAcknowledged": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoff.status").value("TRANSFERRED"))
                .andExpect(jsonPath("$.handoff.activeItemCount").value(2))
                .andExpect(jsonPath("$.handoff.incompleteItemCount").value(1))
                .andDo(document(
                        "transferRoleHandoff",
                        TRANSFER_ROLE_HANDOFF,
                        roleHandoffPathParameters(),
                        accessKeyHeader(),
                        requestFields(
                                requestField(
                                        WorkspaceRequests.TransferRoleHandoffRequest.class,
                                        "confirmedByMemberId",
                                        "전달을 확인했다고 선언한 이전 담당자 UUID"
                                ),
                                requestField(
                                        WorkspaceRequests.TransferRoleHandoffRequest.class,
                                        "warningAcknowledged",
                                        "미완료 항목 또는 자료 없음 경고 확인 여부"
                                )
                        ),
                        responseFields(roleHandoffTransitionResponseFields())));
    }

    @DisplayName("역할 바통 수락 API는 다음 담당자의 확인과 역할 배정을 함께 반영한다")
    @Test
    void documentsAcceptRoleHandoff() throws Exception {
        when(useCase.acceptRoleHandoff(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(ROLE_HANDOFF_ID),
                eq(ACCESS_KEY),
                any(WorkspaceUseCase.ConfirmRoleHandoffCommand.class)
        )).thenReturn(roleHandoffTransitionResult(RoleHandoffStatus.ACCEPTED));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}"
                                + "/handoffs/{handoffId}/acceptance",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID,
                        ROLE_HANDOFF_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "confirmedByMemberId": "33333333-3333-3333-3333-444444444444"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoff.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.role.currentMemberId").value(NEXT_MEMBER_ID.toString()))
                .andExpect(jsonPath("$.role.nextMemberId").value(nullValue()))
                .andDo(document(
                        "acceptRoleHandoff",
                        ACCEPT_ROLE_HANDOFF,
                        roleHandoffPathParameters(),
                        accessKeyHeader(),
                        requestFields(requestField(
                                WorkspaceRequests.ConfirmRoleHandoffRequest.class,
                                "confirmedByMemberId",
                                "수락을 확인했다고 선언한 다음 담당자 UUID"
                        )),
                        responseFields(roleHandoffTransitionResponseFields())));
    }

    @DisplayName("역할 바통 취소 API는 수락 전 바통과 다음 담당자 예약을 되돌린다")
    @Test
    void documentsCancelRoleHandoff() throws Exception {
        when(useCase.cancelRoleHandoff(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(ROLE_HANDOFF_ID),
                eq(ACCESS_KEY),
                any(WorkspaceUseCase.ConfirmRoleHandoffCommand.class)
        )).thenReturn(roleHandoffTransitionResult(RoleHandoffStatus.CANCELLED));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}"
                                + "/handoffs/{handoffId}/cancellation",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID,
                        ROLE_HANDOFF_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "confirmedByMemberId": "33333333-3333-3333-3333-333333333333"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handoff.status").value("CANCELLED"))
                .andExpect(jsonPath("$.role.nextMemberId").value(nullValue()))
                .andDo(document(
                        "cancelRoleHandoff",
                        CANCEL_ROLE_HANDOFF,
                        roleHandoffPathParameters(),
                        accessKeyHeader(),
                        requestFields(requestField(
                                WorkspaceRequests.ConfirmRoleHandoffRequest.class,
                                "confirmedByMemberId",
                                "취소를 확인했다고 선언한 이전 담당자 UUID"
                        )),
                        responseFields(roleHandoffTransitionResponseFields())));
    }

    @DisplayName("이미 열린 역할 바통이 있으면 새 준비 요청은 안정적인 409 상태 충돌을 반환한다")
    @Test
    void documentsPrepareRoleHandoffStateConflict() throws Exception {
        when(useCase.prepareRoleHandoff(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(WorkspaceUseCase.PrepareRoleHandoffCommand.class)
        )).thenThrow(new RoleHandoffStateConflictException(
                "이 역할에는 이미 진행 중인 바통이 있습니다"
        ));

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPrepareRoleHandoffRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_HANDOFF_STATE_CONFLICT"))
                .andDo(document(
                        "prepareRoleHandoffStateConflict",
                        PREPARE_ROLE_HANDOFF,
                        rolePathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("바통 준비도 경고를 확인하지 않으면 전달 API는 확인이 필요한 409를 반환한다")
    @Test
    void documentsTransferRoleHandoffWarningConfirmationRequired() throws Exception {
        when(useCase.transferRoleHandoff(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(ROLE_HANDOFF_ID),
                eq(ACCESS_KEY),
                any(WorkspaceUseCase.TransferRoleHandoffCommand.class)
        )).thenThrow(new RoleHandoffWarningConfirmationRequiredException());

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}"
                                + "/handoffs/{handoffId}/transfer",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID,
                        ROLE_HANDOFF_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "confirmedByMemberId": "33333333-3333-3333-3333-333333333333",
                                  "warningAcknowledged": false
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("ROLE_HANDOFF_WARNING_CONFIRMATION_REQUIRED"))
                .andDo(document(
                        "transferRoleHandoffWarningConfirmationRequired",
                        TRANSFER_ROLE_HANDOFF,
                        roleHandoffPathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("전달할 역할 바통이 없으면 식별 가능한 404 오류를 반환한다")
    @Test
    void documentsTransferRoleHandoffNotFound() throws Exception {
        when(useCase.transferRoleHandoff(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(ROLE_HANDOFF_ID),
                eq(ACCESS_KEY),
                any(WorkspaceUseCase.TransferRoleHandoffCommand.class)
        )).thenThrow(new WorkspaceNotFoundException(
                "ROLE_HANDOFF_NOT_FOUND",
                "역할 바통을 찾을 수 없습니다"
        ));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}"
                                + "/handoffs/{handoffId}/transfer",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID,
                        ROLE_HANDOFF_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "confirmedByMemberId": "33333333-3333-3333-3333-333333333333",
                                  "warningAcknowledged": true
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_HANDOFF_NOT_FOUND"))
                .andDo(document(
                        "transferRoleHandoffNotFound",
                        TRANSFER_ROLE_HANDOFF,
                        roleHandoffPathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("수락할 역할 바통이 없으면 식별 가능한 404 오류를 반환한다")
    @Test
    void documentsAcceptRoleHandoffNotFound() throws Exception {
        when(useCase.acceptRoleHandoff(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(ROLE_HANDOFF_ID),
                eq(ACCESS_KEY),
                any(WorkspaceUseCase.ConfirmRoleHandoffCommand.class)
        )).thenThrow(new WorkspaceNotFoundException(
                "ROLE_HANDOFF_NOT_FOUND",
                "역할 바통을 찾을 수 없습니다"
        ));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}"
                                + "/handoffs/{handoffId}/acceptance",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID,
                        ROLE_HANDOFF_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfirmRoleHandoffRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_HANDOFF_NOT_FOUND"))
                .andDo(document(
                        "acceptRoleHandoffNotFound",
                        ACCEPT_ROLE_HANDOFF,
                        roleHandoffPathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("잘못된 확인자가 역할 바통을 수락하면 안정적인 409 상태 충돌을 반환한다")
    @Test
    void documentsAcceptRoleHandoffStateConflict() throws Exception {
        when(useCase.acceptRoleHandoff(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(ROLE_HANDOFF_ID),
                eq(ACCESS_KEY),
                any(WorkspaceUseCase.ConfirmRoleHandoffCommand.class)
        )).thenThrow(new RoleHandoffStateConflictException(
                "다음 담당자 명의로 수락을 확인해 주세요"
        ));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}"
                                + "/handoffs/{handoffId}/acceptance",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID,
                        ROLE_HANDOFF_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfirmRoleHandoffRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_HANDOFF_STATE_CONFLICT"))
                .andDo(document(
                        "acceptRoleHandoffStateConflict",
                        ACCEPT_ROLE_HANDOFF,
                        roleHandoffPathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("취소할 역할 바통이 없으면 식별 가능한 404 오류를 반환한다")
    @Test
    void documentsCancelRoleHandoffNotFound() throws Exception {
        when(useCase.cancelRoleHandoff(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(ROLE_HANDOFF_ID),
                eq(ACCESS_KEY),
                any(WorkspaceUseCase.ConfirmRoleHandoffCommand.class)
        )).thenThrow(new WorkspaceNotFoundException(
                "ROLE_HANDOFF_NOT_FOUND",
                "역할 바통을 찾을 수 없습니다"
        ));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}"
                                + "/handoffs/{handoffId}/cancellation",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID,
                        ROLE_HANDOFF_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfirmRoleHandoffRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_HANDOFF_NOT_FOUND"))
                .andDo(document(
                        "cancelRoleHandoffNotFound",
                        CANCEL_ROLE_HANDOFF,
                        roleHandoffPathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("수락이 끝난 역할 바통을 취소하면 안정적인 409 상태 충돌을 반환한다")
    @Test
    void documentsCancelRoleHandoffStateConflict() throws Exception {
        when(useCase.cancelRoleHandoff(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(ROLE_HANDOFF_ID),
                eq(ACCESS_KEY),
                any(WorkspaceUseCase.ConfirmRoleHandoffCommand.class)
        )).thenThrow(new RoleHandoffStateConflictException(
                "수락이 끝난 바통은 취소할 수 없습니다"
        ));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}"
                                + "/handoffs/{handoffId}/cancellation",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID,
                        ROLE_HANDOFF_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validConfirmRoleHandoffRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_HANDOFF_STATE_CONFLICT"))
                .andDo(document(
                        "cancelRoleHandoffStateConflict",
                        CANCEL_ROLE_HANDOFF,
                        roleHandoffPathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("루틴 생성 API는 완료 상태가 없는 반복 실행 정의를 반환한다")
    @Test
    void documentsCreateRoutine() throws Exception {
        when(useCase.createRoutine(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateRoutineCommand.class)
        ))
                .thenReturn(routineResult());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/routines", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "모임 전 질문 모으기",
                                  "phase": "BEFORE",
                                  "dueLabel": "모임 하루 전",
                                  "deadlineDayOffset": -1,
                                  "deadlineTime": "22:00",
                                  "ownerRoleId": "44444444-4444-4444-4444-444444444444",
                                  "detail": "공통 질문을 한 문서에 정리합니다"
                                }
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andDo(document(
                        "createRoutine",
                        CREATE_ROUTINE,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        requestFields(
                                requestField(WorkspaceRequests.CreateRoutineRequest.class,
                                        "title", "루틴 제목"),
                                requestEnumField(WorkspaceRequests.CreateRoutineRequest.class,
                                        RoutinePhase.class, "phase", "실행 단계: BEFORE, DURING, AFTER"),
                                requestField(WorkspaceRequests.CreateRoutineRequest.class,
                                        "dueLabel", "사용자에게 보일 기한 문구"),
                                optionalRequestField(WorkspaceRequests.CreateRoutineRequest.class,
                                        "deadlineDayOffset", "모임 날짜 기준 실제 마감일 오프셋"),
                                optionalRequestField(WorkspaceRequests.CreateRoutineRequest.class,
                                        "deadlineTime", "시즌 시간대 기준 실제 마감 시각"),
                                requestField(WorkspaceRequests.CreateRoutineRequest.class,
                                        "ownerRoleId", "담당 역할 UUID"),
                                requestField(WorkspaceRequests.CreateRoutineRequest.class,
                                        "detail", "실행 방법")
                        ),
                        responseFields(routineResponseFields())));
    }

    @DisplayName("루틴 수정 API는 완료 상태와 분리된 반복 실행 정의를 반환한다")
    @Test
    void documentsUpdateRoutine() throws Exception {
        when(useCase.updateRoutine(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROUTINE_ID),
                eq(ACCESS_KEY),
                any(UpdateRoutineCommand.class)
        ))
                .thenReturn(updatedRoutineResult());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROUTINE_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateRoutineRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ROUTINE_ID.toString()))
                .andExpect(jsonPath("$.title").value("모임 후 회고 모으기"))
                .andExpect(jsonPath("$.phase").value("AFTER"))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andDo(document(
                        "updateRoutine",
                        UPDATE_ROUTINE,
                        routinePathParameters(),
                        accessKeyHeader(),
                        requestFields(
                                requestField(WorkspaceRequests.UpdateRoutineRequest.class,
                                        "title", "루틴 제목"),
                                requestEnumField(WorkspaceRequests.UpdateRoutineRequest.class,
                                        RoutinePhase.class, "phase", "실행 단계: BEFORE, DURING, AFTER"),
                                requestField(WorkspaceRequests.UpdateRoutineRequest.class,
                                        "dueLabel", "사용자에게 보일 기한 문구"),
                                optionalRequestField(WorkspaceRequests.UpdateRoutineRequest.class,
                                        "deadlineDayOffset", "모임 날짜 기준 실제 마감일 오프셋"),
                                optionalRequestField(WorkspaceRequests.UpdateRoutineRequest.class,
                                        "deadlineTime", "시즌 시간대 기준 실제 마감 시각"),
                                requestField(WorkspaceRequests.UpdateRoutineRequest.class,
                                        "ownerRoleId", "담당 역할 UUID"),
                                requestField(WorkspaceRequests.UpdateRoutineRequest.class,
                                        "detail", "실행 방법")
                        ),
                        responseFields(routineResponseFields())));
    }

    @DisplayName("시즌 회차 생성 API는 현재 루틴의 실행 항목을 포함해 반환한다")
    @Test
    void documentsCreateSeasonRound() throws Exception {
        when(useCase.createSeasonRound(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateSeasonRoundCommand.class)
        )).thenReturn(seasonRoundResult(RoutineStatus.WAITING));

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/rounds", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validSeasonRoundRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ROUND_ID.toString()))
                .andExpect(jsonPath("$.name").value("3회차"))
                .andExpect(jsonPath("$.meetingDate").value("2026-07-27"))
                .andExpect(jsonPath("$.routineExecutions[0].status").value("WAITING"))
                .andDo(document(
                        "createSeasonRound",
                        CREATE_SEASON_ROUND,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        requestFields(
                                requestField(WorkspaceRequests.CreateSeasonRoundRequest.class,
                                        "name", "시즌 안에서 유일한 회차 이름"),
                                requestField(WorkspaceRequests.CreateSeasonRoundRequest.class,
                                        "meetingDate", "모임 날짜(ISO-8601 날짜)")
                        ),
                        responseFields(seasonRoundResponseFields())));
    }

    @DisplayName("시즌 회차 수정 API는 실행 스냅샷을 유지한 전체 회차 표현을 반환한다")
    @Test
    void documentsUpdateSeasonRound() throws Exception {
        when(useCase.updateSeasonRound(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROUND_ID),
                eq(ACCESS_KEY),
                any(UpdateSeasonRoundCommand.class)
        )).thenReturn(updatedSeasonRoundResult(null));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateSeasonRoundRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ROUND_ID.toString()))
                .andExpect(jsonPath("$.name").value("세 번째 모임"))
                .andExpect(jsonPath("$.meetingDate").value("2026-07-28"))
                .andExpect(jsonPath("$.routineExecutions[0].id").value(EXECUTION_ID.toString()))
                .andExpect(jsonPath("$.archivedAt").value(nullValue()))
                .andDo(document(
                        "updateSeasonRound",
                        UPDATE_SEASON_ROUND,
                        seasonRoundPathParameters(),
                        accessKeyHeader(),
                        requestFields(
                                requestField(WorkspaceRequests.UpdateSeasonRoundRequest.class,
                                        "name", "시즌 안에서 유일한 회차 이름"),
                                requestField(WorkspaceRequests.UpdateSeasonRoundRequest.class,
                                        "meetingDate", "시즌 기간 안의 모임 날짜")
                        ),
                        responseFields(seasonRoundResponseFields())));
    }

    @DisplayName("시즌 회차 보관 API는 보관한 회차를 실행 기록과 함께 복원한다")
    @Test
    void documentsRestoreSeasonRound() throws Exception {
        when(useCase.updateSeasonRoundArchive(
                TEAM_ID,
                SEASON_ID,
                ROUND_ID,
                ACCESS_KEY,
                false
        )).thenReturn(updatedSeasonRoundResult(null));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routineExecutions[0].id").value(EXECUTION_ID.toString()))
                .andExpect(jsonPath("$.archivedAt").value(nullValue()))
                .andDo(document(
                        "updateSeasonRoundArchiveRestore",
                        UPDATE_SEASON_ROUND_ARCHIVE,
                        seasonRoundPathParameters(),
                        accessKeyHeader(),
                        requestFields(requestField(
                                WorkspaceRequests.ArchiveRequest.class,
                                "archived",
                                "true면 보관, false면 복원"
                        )),
                        responseFields(seasonRoundResponseFields())));
    }

    @DisplayName("시즌 회차 보관 API는 실행 기록과 서버가 기록한 보관 시각을 함께 반환한다")
    @Test
    void documentsUpdateSeasonRoundArchive() throws Exception {
        Instant archivedAt = Instant.parse("2026-07-20T04:05:06Z");
        when(useCase.updateSeasonRoundArchive(
                TEAM_ID,
                SEASON_ID,
                ROUND_ID,
                ACCESS_KEY,
                true
        )).thenReturn(updatedSeasonRoundResult(archivedAt));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routineExecutions[0].id").value(EXECUTION_ID.toString()))
                .andExpect(jsonPath("$.archivedAt").value("2026-07-20T04:05:06Z"))
                .andDo(document(
                        "updateSeasonRoundArchive",
                        UPDATE_SEASON_ROUND_ARCHIVE,
                        seasonRoundPathParameters(),
                        accessKeyHeader(),
                        requestFields(requestField(
                                WorkspaceRequests.ArchiveRequest.class,
                                "archived",
                                "true면 보관, false면 복원"
                        )),
                        responseFields(seasonRoundResponseFields())));
    }

    @DisplayName("회차 루틴 실행 완료 API는 completed 값에 따라 DONE 상태를 반환한다")
    @Test
    void documentsUpdateRoutineExecutionCompletion() throws Exception {
        when(useCase.updateRoutineExecutionCompletion(
                TEAM_ID,
                SEASON_ID,
                ROUND_ID,
                EXECUTION_ID,
                ACCESS_KEY,
                true
        )).thenReturn(routineExecutionResult(RoutineStatus.DONE));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}"
                                + "/routine-executions/{executionId}/completion",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID,
                        EXECUTION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(EXECUTION_ID.toString()))
                .andExpect(jsonPath("$.roundId").value(ROUND_ID.toString()))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andDo(document(
                        "updateRoutineExecutionCompletion",
                        UPDATE_ROUTINE_EXECUTION_COMPLETION,
                        routineExecutionPathParameters(),
                        accessKeyHeader(),
                        requestFields(requestField(
                                WorkspaceRequests.UpdateRoutineExecutionCompletionRequest.class,
                                "completed", "완료 여부")),
                        responseFields(routineExecutionResponseFields())));
    }

    @DisplayName("시즌 회차 이름이 비어 있으면 400 입력 오류를 반환한다")
    @Test
    void documentsCreateSeasonRoundInvalidInput() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/rounds", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": " ",
                                  "meetingDate": "2026-07-27"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andDo(document(
                        "createSeasonRoundInvalidInput",
                        CREATE_SEASON_ROUND,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("같은 시즌에 회차 이름이 중복되면 409 오류를 반환한다")
    @Test
    void documentsCreateSeasonRoundNameConflict() throws Exception {
        when(useCase.createSeasonRound(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateSeasonRoundCommand.class)
        )).thenThrow(new SeasonRoundNameConflictException());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/rounds", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validSeasonRoundRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUND_NAME_CONFLICT"))
                .andExpect(jsonPath("$.message").value("같은 시즌에 동일한 회차 이름을 사용할 수 없습니다"))
                .andDo(document(
                        "createSeasonRoundNameConflict",
                        CREATE_SEASON_ROUND,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("시즌 회차 수정 API는 잘못된 입력을 400 오류로 반환한다")
    @Test
    void documentsUpdateSeasonRoundInvalidInput() throws Exception {
        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": " ",
                                  "meetingDate": null
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andDo(document(
                        "updateSeasonRoundInvalidInput",
                        UPDATE_SEASON_ROUND,
                        seasonRoundPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("시즌 회차 수정과 보관 API는 접근 키가 틀리면 403 오류를 반환한다")
    @Test
    void documentsSeasonRoundRevisionAccessDenied() throws Exception {
        when(useCase.updateSeasonRound(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROUND_ID),
                eq(ACCESS_KEY),
                any(UpdateSeasonRoundCommand.class)
        )).thenThrow(new WorkspaceAccessDeniedException());
        when(useCase.updateSeasonRoundArchive(TEAM_ID, SEASON_ID, ROUND_ID, ACCESS_KEY, true))
                .thenThrow(new WorkspaceAccessDeniedException());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateSeasonRoundRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"))
                .andDo(document(
                        "updateSeasonRoundAccessDenied",
                        UPDATE_SEASON_ROUND,
                        seasonRoundPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"))
                .andDo(document(
                        "updateSeasonRoundArchiveAccessDenied",
                        UPDATE_SEASON_ROUND_ARCHIVE,
                        seasonRoundPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("시즌 회차 수정 API는 없는 회차, 중복 이름과 겹친 변경을 구분한다")
    @Test
    void documentsUpdateSeasonRoundErrors() throws Exception {
        when(useCase.updateSeasonRound(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROUND_ID),
                eq(ACCESS_KEY),
                any(UpdateSeasonRoundCommand.class)
        ))
                .thenThrow(new WorkspaceNotFoundException(
                        "SEASON_ROUND_NOT_FOUND",
                        "회차를 찾을 수 없습니다"
                ))
                .thenThrow(new SeasonRoundNameConflictException())
                .thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateSeasonRoundRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEASON_ROUND_NOT_FOUND"))
                .andDo(document(
                        "updateSeasonRoundNotFound",
                        UPDATE_SEASON_ROUND,
                        seasonRoundPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateSeasonRoundRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUND_NAME_CONFLICT"))
                .andDo(document(
                        "updateSeasonRoundNameConflict",
                        UPDATE_SEASON_ROUND,
                        seasonRoundPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateSeasonRoundRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"))
                .andDo(document(
                        "updateSeasonRoundContentConflict",
                        UPDATE_SEASON_ROUND,
                        seasonRoundPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("시즌 회차 보관 API는 보관 여부가 없으면 400 입력 오류를 반환한다")
    @Test
    void documentsUpdateSeasonRoundArchiveInvalidInput() throws Exception {
        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andDo(document(
                        "updateSeasonRoundArchiveInvalidInput",
                        UPDATE_SEASON_ROUND_ARCHIVE,
                        seasonRoundPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("시즌 회차 보관 API는 없는 회차와 겹친 변경을 404와 409로 구분한다")
    @Test
    void documentsUpdateSeasonRoundArchiveErrors() throws Exception {
        when(useCase.updateSeasonRoundArchive(TEAM_ID, SEASON_ID, ROUND_ID, ACCESS_KEY, true))
                .thenThrow(new WorkspaceNotFoundException(
                        "SEASON_ROUND_NOT_FOUND",
                        "회차를 찾을 수 없습니다"
                ))
                .thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEASON_ROUND_NOT_FOUND"))
                .andDo(document(
                        "updateSeasonRoundArchiveNotFound",
                        UPDATE_SEASON_ROUND_ARCHIVE,
                        seasonRoundPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"))
                .andDo(document(
                        "updateSeasonRoundArchiveContentConflict",
                        UPDATE_SEASON_ROUND_ARCHIVE,
                        seasonRoundPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("결정 생성 API는 서버 시각과 작성자 이름을 포함해 반환한다")
    @Test
    void documentsCreateDecision() throws Exception {
        when(useCase.createDecision(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateDecisionCommand.class)
        ))
                .thenReturn(decisionResult());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/decisions", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
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
                .andDo(document(
                        "createDecision",
                        CREATE_DECISION,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        requestFields(
                                requestField(WorkspaceRequests.CreateDecisionRequest.class,
                                        "title", "결정 제목"),
                                requestField(WorkspaceRequests.CreateDecisionRequest.class,
                                        "reason", "결정 이유"),
                                optionalRequestField(WorkspaceRequests.CreateDecisionRequest.class,
                                        "alternative", "검토한 대안"),
                                requestField(WorkspaceRequests.CreateDecisionRequest.class,
                                        "authorMemberId", "작성자 구성원 UUID"),
                                requestStringArrayField(WorkspaceRequests.CreateDecisionRequest.class,
                                        "roleIds", "roleIds[]", "중복 없는 관련 역할 UUID 목록")
                        ),
                        responseFields(decisionResponseFields())));
    }

    @DisplayName("결정 수정 API는 생성 시각을 유지하며 정정한 전체 표현을 반환한다")
    @Test
    void documentsUpdateDecision() throws Exception {
        when(useCase.updateDecision(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(DECISION_ID),
                eq(ACCESS_KEY),
                any(UpdateDecisionCommand.class)
        )).thenReturn(decisionResult());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}",
                        TEAM_ID,
                        SEASON_ID,
                        DECISION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateDecisionRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DECISION_ID.toString()))
                .andExpect(jsonPath("$.authorMemberId").value(MEMBER_ID.toString()))
                .andExpect(jsonPath("$.createdAt").value("2026-07-20T03:04:05Z"))
                .andDo(document(
                        "updateDecision",
                        UPDATE_DECISION,
                        decisionPathParameters(),
                        accessKeyHeader(),
                        requestFields(
                                requestField(WorkspaceRequests.UpdateDecisionRequest.class,
                                        "title", "결정 제목"),
                                requestField(WorkspaceRequests.UpdateDecisionRequest.class,
                                        "reason", "결정 이유"),
                                optionalRequestField(WorkspaceRequests.UpdateDecisionRequest.class,
                                        "alternative", "검토한 대안"),
                                requestField(WorkspaceRequests.UpdateDecisionRequest.class,
                                        "authorMemberId", "작성자 구성원 UUID"),
                                requestStringArrayField(WorkspaceRequests.UpdateDecisionRequest.class,
                                        "roleIds", "roleIds[]", "중복 없는 관련 역할 UUID 목록")
                        ),
                        responseFields(decisionResponseFields())));
    }

    @DisplayName("결정 보관 API는 서버가 기록한 보관 시각을 반환한다")
    @Test
    void documentsUpdateDecisionArchive() throws Exception {
        Instant archivedAt = Instant.parse("2026-07-20T04:05:06Z");
        when(useCase.updateDecisionArchive(TEAM_ID, SEASON_ID, DECISION_ID, ACCESS_KEY, true))
                .thenReturn(decisionResult(archivedAt));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}/archive",
                        TEAM_ID,
                        SEASON_ID,
                        DECISION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value("2026-07-20T04:05:06Z"))
                .andDo(document(
                        "updateDecisionArchive",
                        UPDATE_DECISION_ARCHIVE,
                        decisionPathParameters(),
                        accessKeyHeader(),
                        requestFields(requestField(
                                WorkspaceRequests.ArchiveRequest.class,
                                "archived",
                                "true면 보관, false면 복원"
                        )),
                        responseFields(decisionResponseFields())));
    }

    @DisplayName("인수인계 항목 생성 API는 초기 완료 여부를 false로 정해 반환한다")
    @Test
    void documentsCreateHandoffItem() throws Exception {
        when(useCase.createHandoffItem(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateHandoffItemCommand.class)
        ))
                .thenReturn(handoffItemResult(false));

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
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
                .andExpect(jsonPath("$.createdAt").value("2026-07-20T03:04:05Z"))
                .andDo(document(
                        "createHandoffItem",
                        CREATE_HANDOFF_ITEM,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        requestFields(
                                requestField(WorkspaceRequests.CreateHandoffItemRequest.class,
                                        "roleId", "소유 역할 UUID"),
                                requestField(WorkspaceRequests.CreateHandoffItemRequest.class,
                                        "label", "인수인계할 내용"),
                                requestEnumField(
                                        WorkspaceRequests.CreateHandoffItemRequest.class,
                                        HandoffCategory.class,
                                        "category",
                                        "분류: RESPONSIBILITY, ROUTINE, RESOURCE, ADVICE")
                        ),
                        responseFields(handoffItemResponseFields())));
    }

    @DisplayName("인수인계 항목 수정 API는 완료 여부를 유지하며 정정한 전체 표현을 반환한다")
    @Test
    void documentsUpdateHandoffItem() throws Exception {
        when(useCase.updateHandoffItem(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(HANDOFF_ITEM_ID),
                eq(ACCESS_KEY),
                any(UpdateHandoffItemCommand.class)
        )).thenReturn(handoffItemResult(true));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}",
                        TEAM_ID,
                        SEASON_ID,
                        HANDOFF_ITEM_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateHandoffItemRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(HANDOFF_ITEM_ID.toString()))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.createdAt").value("2026-07-20T03:04:05Z"))
                .andDo(document(
                        "updateHandoffItem",
                        UPDATE_HANDOFF_ITEM,
                        handoffItemPathParameters(),
                        accessKeyHeader(),
                        requestFields(
                                requestField(WorkspaceRequests.UpdateHandoffItemRequest.class,
                                        "roleId", "소유 역할 UUID"),
                                requestField(WorkspaceRequests.UpdateHandoffItemRequest.class,
                                        "label", "인수인계할 내용"),
                                requestEnumField(
                                        WorkspaceRequests.UpdateHandoffItemRequest.class,
                                        HandoffCategory.class,
                                        "category",
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
                .andExpect(jsonPath("$.createdAt").value("2026-07-20T03:04:05Z"))
                .andDo(document(
                        "updateHandoffItemCompletion",
                        UPDATE_HANDOFF_ITEM_COMPLETION,
                        pathParameters(
                                parameterWithName("teamId").description("팀 UUID"),
                                parameterWithName("seasonId").description("시즌 UUID"),
                                parameterWithName("itemId").description("인수인계 항목 UUID")
                        ),
                        accessKeyHeader(),
                        requestFields(requestField(WorkspaceRequests.CompletionRequest.class,
                                "completed", "완료 여부")),
                        responseFields(handoffItemResponseFields())));
    }

    @DisplayName("인수인계 항목 보관 API는 서버가 기록한 보관 시각을 반환한다")
    @Test
    void documentsUpdateHandoffItemArchive() throws Exception {
        Instant archivedAt = Instant.parse("2026-07-20T04:05:06Z");
        when(useCase.updateHandoffItemArchive(
                TEAM_ID,
                SEASON_ID,
                HANDOFF_ITEM_ID,
                ACCESS_KEY,
                true
        )).thenReturn(handoffItemResult(false, archivedAt));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/archive",
                        TEAM_ID,
                        SEASON_ID,
                        HANDOFF_ITEM_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value("2026-07-20T04:05:06Z"))
                .andExpect(jsonPath("$.createdAt").value("2026-07-20T03:04:05Z"))
                .andDo(document(
                        "updateHandoffItemArchive",
                        UPDATE_HANDOFF_ITEM_ARCHIVE,
                        handoffItemPathParameters(),
                        accessKeyHeader(),
                        requestFields(requestField(
                                WorkspaceRequests.ArchiveRequest.class,
                                "archived",
                                "true면 보관, false면 복원"
                        )),
                        responseFields(handoffItemResponseFields())));
    }

    @DisplayName("결정 수정 API는 없는 기록과 겹친 변경을 404와 409로 구분한다")
    @Test
    void documentsUpdateDecisionErrors() throws Exception {
        when(useCase.updateDecision(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(DECISION_ID),
                eq(ACCESS_KEY),
                any(UpdateDecisionCommand.class)
        ))
                .thenThrow(new WorkspaceNotFoundException(
                        "DECISION_NOT_FOUND",
                        "결정 기록을 찾을 수 없습니다"
                ))
                .thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}",
                        TEAM_ID,
                        SEASON_ID,
                        DECISION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateDecisionRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DECISION_NOT_FOUND"))
                .andDo(document(
                        "updateDecisionNotFound",
                        UPDATE_DECISION,
                        decisionPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}",
                        TEAM_ID,
                        SEASON_ID,
                        DECISION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateDecisionRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"))
                .andDo(document(
                        "updateDecisionContentConflict",
                        UPDATE_DECISION,
                        decisionPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("결정 보관 API는 없는 기록과 겹친 변경을 404와 409로 구분한다")
    @Test
    void documentsUpdateDecisionArchiveErrors() throws Exception {
        when(useCase.updateDecisionArchive(TEAM_ID, SEASON_ID, DECISION_ID, ACCESS_KEY, true))
                .thenThrow(new WorkspaceNotFoundException(
                        "DECISION_NOT_FOUND",
                        "결정 기록을 찾을 수 없습니다"
                ))
                .thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}/archive",
                        TEAM_ID,
                        SEASON_ID,
                        DECISION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DECISION_NOT_FOUND"))
                .andDo(document(
                        "updateDecisionArchiveNotFound",
                        UPDATE_DECISION_ARCHIVE,
                        decisionPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}/archive",
                        TEAM_ID,
                        SEASON_ID,
                        DECISION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"))
                .andDo(document(
                        "updateDecisionArchiveContentConflict",
                        UPDATE_DECISION_ARCHIVE,
                        decisionPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("인수인계 항목 수정 API는 없는 항목과 겹친 변경을 404와 409로 구분한다")
    @Test
    void documentsUpdateHandoffItemErrors() throws Exception {
        when(useCase.updateHandoffItem(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(HANDOFF_ITEM_ID),
                eq(ACCESS_KEY),
                any(UpdateHandoffItemCommand.class)
        ))
                .thenThrow(new WorkspaceNotFoundException(
                        "HANDOFF_ITEM_NOT_FOUND",
                        "인수인계 항목을 찾을 수 없습니다"
                ))
                .thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}",
                        TEAM_ID,
                        SEASON_ID,
                        HANDOFF_ITEM_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateHandoffItemRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HANDOFF_ITEM_NOT_FOUND"))
                .andDo(document(
                        "updateHandoffItemNotFound",
                        UPDATE_HANDOFF_ITEM,
                        handoffItemPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}",
                        TEAM_ID,
                        SEASON_ID,
                        HANDOFF_ITEM_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateHandoffItemRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"))
                .andDo(document(
                        "updateHandoffItemContentConflict",
                        UPDATE_HANDOFF_ITEM,
                        handoffItemPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("인수인계 완료 API는 없는 항목과 겹친 변경을 404와 409로 구분한다")
    @Test
    void documentsUpdateHandoffItemCompletionErrors() throws Exception {
        when(useCase.updateHandoffItemCompletion(
                TEAM_ID,
                SEASON_ID,
                HANDOFF_ITEM_ID,
                ACCESS_KEY,
                true
        ))
                .thenThrow(new WorkspaceNotFoundException(
                        "HANDOFF_ITEM_NOT_FOUND",
                        "인수인계 항목을 찾을 수 없습니다"
                ))
                .thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/completion",
                        TEAM_ID,
                        SEASON_ID,
                        HANDOFF_ITEM_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HANDOFF_ITEM_NOT_FOUND"))
                .andDo(document(
                        "updateHandoffItemCompletionNotFound",
                        UPDATE_HANDOFF_ITEM_COMPLETION,
                        handoffItemPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/completion",
                        TEAM_ID,
                        SEASON_ID,
                        HANDOFF_ITEM_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\": true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"))
                .andDo(document(
                        "updateHandoffItemCompletionContentConflict",
                        UPDATE_HANDOFF_ITEM_COMPLETION,
                        handoffItemPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("인수인계 보관 API는 없는 항목과 겹친 변경을 404와 409로 구분한다")
    @Test
    void documentsUpdateHandoffItemArchiveErrors() throws Exception {
        when(useCase.updateHandoffItemArchive(
                TEAM_ID,
                SEASON_ID,
                HANDOFF_ITEM_ID,
                ACCESS_KEY,
                true
        ))
                .thenThrow(new WorkspaceNotFoundException(
                        "HANDOFF_ITEM_NOT_FOUND",
                        "인수인계 항목을 찾을 수 없습니다"
                ))
                .thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/archive",
                        TEAM_ID,
                        SEASON_ID,
                        HANDOFF_ITEM_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HANDOFF_ITEM_NOT_FOUND"))
                .andDo(document(
                        "updateHandoffItemArchiveNotFound",
                        UPDATE_HANDOFF_ITEM_ARCHIVE,
                        handoffItemPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/archive",
                        TEAM_ID,
                        SEASON_ID,
                        HANDOFF_ITEM_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"))
                .andDo(document(
                        "updateHandoffItemArchiveContentConflict",
                        UPDATE_HANDOFF_ITEM_ARCHIVE,
                        handoffItemPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("역할 자료 생성 API는 역할에 브라우저에서 열 수 있는 외부 링크를 연결해 반환한다")
    @Test
    void documentsCreateRoleResource() throws Exception {
        when(useCase.createRoleResource(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateRoleResourceCommand.class)
        )).thenReturn(roleResourceResult());

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources",
                        TEAM_ID,
                        SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleId": "44444444-4444-4444-4444-444444444444",
                                  "title": "질문 정리 가이드",
                                  "url": "https://docs.example.com/question-guide",
                                  "description": "질문을 모으고 분류하는 기준"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ROLE_RESOURCE_ID.toString()))
                .andExpect(jsonPath("$.roleId").value(ROLE_ID.toString()))
                .andExpect(jsonPath("$.url").value("https://docs.example.com/question-guide"))
                .andExpect(jsonPath("$.createdAt").value("2026-07-20T03:04:05Z"))
                .andDo(document(
                        "createRoleResource",
                        CREATE_ROLE_RESOURCE,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        requestFields(
                                requestField(WorkspaceRequests.CreateRoleResourceRequest.class,
                                        "roleId", "자료를 소유하는 역할 UUID"),
                                requestField(WorkspaceRequests.CreateRoleResourceRequest.class,
                                        "title", "자료 제목"),
                                requestField(WorkspaceRequests.CreateRoleResourceRequest.class,
                                        "url", "사용자 정보가 없는 http 또는 https 외부 링크"),
                                optionalRequestField(WorkspaceRequests.CreateRoleResourceRequest.class,
                                        "description", "자료 사용 맥락")
                        ),
                        responseFields(roleResourceResponseFields())));
    }

    @DisplayName("역할 자료 수정 API는 소유 역할과 링크 정보를 바꿔 반환한다")
    @Test
    void documentsUpdateRoleResource() throws Exception {
        when(useCase.updateRoleResource(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_RESOURCE_ID),
                eq(ACCESS_KEY),
                any(UpdateRoleResourceCommand.class)
        )).thenReturn(updatedRoleResourceResult());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_RESOURCE_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleId": "44444444-4444-4444-4444-444444444444",
                                  "title": "질문 정리 가이드 개정판",
                                  "url": "https://docs.example.com/question-guide-v2",
                                  "description": "이번 시즌에 맞춘 질문 분류 기준"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ROLE_RESOURCE_ID.toString()))
                .andExpect(jsonPath("$.roleId").value(ROLE_ID.toString()))
                .andExpect(jsonPath("$.title").value("질문 정리 가이드 개정판"))
                .andExpect(jsonPath("$.createdAt").value("2026-07-20T03:04:05Z"))
                .andDo(document(
                        "updateRoleResource",
                        UPDATE_ROLE_RESOURCE,
                        roleResourcePathParameters(),
                        accessKeyHeader(),
                        requestFields(
                                requestField(WorkspaceRequests.UpdateRoleResourceRequest.class,
                                        "roleId", "자료를 소유하는 역할 UUID"),
                                requestField(WorkspaceRequests.UpdateRoleResourceRequest.class,
                                        "title", "자료 제목"),
                                requestField(WorkspaceRequests.UpdateRoleResourceRequest.class,
                                        "url", "사용자 정보가 없는 http 또는 https 외부 링크"),
                                optionalRequestField(WorkspaceRequests.UpdateRoleResourceRequest.class,
                                        "description", "자료 사용 맥락")
                        ),
                        responseFields(roleResourceResponseFields())));
    }

    @DisplayName("허용하지 않는 역할 자료 URL은 안정적인 400 오류 계약을 반환한다")
    @Test
    void documentsCreateRoleResourceInvalidInput() throws Exception {
        when(useCase.createRoleResource(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateRoleResourceCommand.class)
        )).thenThrow(new DomainValidationException(
                "자료 URL은 사용자 정보가 없는 http 또는 https 주소여야 합니다"));

        mockMvc.perform(post(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources",
                        TEAM_ID,
                        SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleId": "44444444-4444-4444-4444-444444444444",
                                  "title": "내부 파일",
                                  "url": "file:///etc/passwd",
                                  "description": null
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andDo(document(
                        "createRoleResourceInvalidInput",
                        CREATE_ROLE_RESOURCE,
                        workspacePathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("없는 역할 자료를 수정하면 안정적인 404 오류 계약을 반환한다")
    @Test
    void documentsUpdateRoleResourceNotFound() throws Exception {
        when(useCase.updateRoleResource(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_RESOURCE_ID),
                eq(ACCESS_KEY),
                any(UpdateRoleResourceCommand.class)
        )).thenThrow(new WorkspaceNotFoundException("ROLE_RESOURCE_NOT_FOUND", "자료를 찾을 수 없습니다"));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_RESOURCE_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateRoleResourceRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_RESOURCE_NOT_FOUND"))
                .andDo(document(
                        "updateRoleResourceNotFound",
                        UPDATE_ROLE_RESOURCE,
                        roleResourcePathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("역할 자료 수정이 겹치면 안정적인 409 충돌 계약을 반환한다")
    @Test
    void documentsUpdateRoleResourceContentConflict() throws Exception {
        when(useCase.updateRoleResource(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_RESOURCE_ID),
                eq(ACCESS_KEY),
                any(UpdateRoleResourceCommand.class)
        )).thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_RESOURCE_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateRoleResourceRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"))
                .andDo(document(
                        "updateRoleResourceContentConflict",
                        UPDATE_ROLE_RESOURCE,
                        roleResourcePathParameters(),
                        responseFields(errorResponseFields())));
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
                .andDo(document(
                        "getWorkspaceAccessDenied",
                        GET_WORKSPACE,
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
                .andDo(document(
                        "createWorkspaceIdempotencyKeyReused",
                        CREATE_WORKSPACE,
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
                .andDo(document(
                        "createWorkspaceIdempotencyKeyConflict",
                        CREATE_WORKSPACE,
                        responseFields(errorResponseFields())));
    }

    @DisplayName("콘텐츠 생성 멱등 키를 다른 요청에 재사용하면 409 오류를 반환한다")
    @Test
    void documentsContentCreationIdempotencyKeyReused() throws Exception {
        when(useCase.createRoutine(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateRoutineCommand.class)
        )).thenThrow(new IdempotencyKeyReusedException());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/routines", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRoutineRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.message").value(
                        "동일한 멱등 키를 의미가 다른 생성 요청에 사용할 수 없습니다"))
                .andDo(document(
                        "createRoutineIdempotencyKeyReused",
                        CREATE_ROUTINE,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("같은 콘텐츠 생성 멱등 키가 동시에 처리되면 재시도 가능한 409 오류를 반환한다")
    @Test
    void documentsContentCreationIdempotencyKeyConflict() throws Exception {
        when(useCase.createRoutine(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateRoutineCommand.class)
        )).thenThrow(new IdempotencyKeyConflictException());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/routines", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRoutineRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        "동일한 멱등 키의 생성 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요"))
                .andDo(document(
                        "createRoutineIdempotencyKeyConflict",
                        CREATE_ROUTINE,
                        workspacePathParameters(),
                        contentCreationHeaders(),
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
                .andDo(document(
                        "rotateAccessKeyReplayExpired",
                        ROTATE_ACCESS_KEY,
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
                .andDo(document(
                        "createWorkspaceInvalidIdempotencyKey",
                        CREATE_WORKSPACE,
                        responseFields(errorResponseFields())));
    }

    @DisplayName("멱등 키가 누락되면 콘텐츠 생성 API는 안정적인 400 입력 오류를 반환한다")
    @Test
    void documentsMissingContentCreationIdempotencyKey() throws Exception {
        when(useCase.createRoutine(
                eq(TEAM_ID),
                eq(SEASON_ID),
                isNull(),
                eq(ACCESS_KEY),
                any(CreateRoutineCommand.class)
        )).thenThrow(new DomainValidationException(
                "멱등 키는 32자 이상 200자 이하의 URL 안전 ASCII 문자여야 합니다"
        ));

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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value(
                        "멱등 키는 32자 이상 200자 이하의 URL 안전 ASCII 문자여야 합니다"))
                .andDo(document(
                        "createRoutineInvalidIdempotencyKey",
                        CREATE_ROUTINE,
                        workspacePathParameters(),
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
                .andDo(document(
                        "createWorkspaceCreationDenied",
                        CREATE_WORKSPACE,
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
                .andDo(document(
                        "recoverAccessKeyDenied",
                        RECOVER_ACCESS_KEY,
                        workspacePathParameters(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("같은 팀에 역할 이름이 중복되면 역할 API는 409 오류 계약을 반환한다")
    @Test
    void documentsRoleNameConflict() throws Exception {
        when(useCase.createRole(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(CONTENT_IDEMPOTENCY_KEY),
                eq(ACCESS_KEY),
                any(CreateRoleCommand.class)
        ))
                .thenThrow(new RoleNameConflictException());

        mockMvc.perform(post("/api/v1/teams/{teamId}/seasons/{seasonId}/roles", TEAM_ID, SEASON_ID)
                        .header("Idempotency-Key", CONTENT_IDEMPOTENCY_KEY)
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
                .andDo(document(
                        "createRoleNameConflict",
                        CREATE_ROLE,
                        workspacePathParameters(),
                        contentCreationHeaders(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("역할 수정 이름이 다른 역할과 겹치면 409 오류 계약을 반환한다")
    @Test
    void documentsUpdateRoleNameConflict() throws Exception {
        when(useCase.updateRole(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(ACCESS_KEY),
                any(UpdateRoleCommand.class)
        ))
                .thenThrow(new RoleNameConflictException());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateRoleRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_CONFLICT"))
                .andDo(document(
                        "updateRoleNameConflict",
                        UPDATE_ROLE,
                        rolePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("수정할 역할이 없으면 역할 수정 API는 식별 가능한 404 오류를 반환한다")
    @Test
    void documentsUpdateRoleNotFound() throws Exception {
        when(useCase.updateRole(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(ACCESS_KEY),
                any(UpdateRoleCommand.class)
        ))
                .thenThrow(new WorkspaceNotFoundException("ROLE_NOT_FOUND", "역할을 찾을 수 없습니다"));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateRoleRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("역할을 찾을 수 없습니다"))
                .andDo(document(
                        "updateRoleNotFound",
                        UPDATE_ROLE,
                        rolePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("역할 수정이 다른 변경과 충돌하면 재시도를 안내하는 409 오류를 반환한다")
    @Test
    void documentsUpdateRoleContentConflict() throws Exception {
        when(useCase.updateRole(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROLE_ID),
                eq(ACCESS_KEY),
                any(UpdateRoleCommand.class)
        ))
                .thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROLE_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateRoleRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"))
                .andDo(document(
                        "updateRoleContentConflict",
                        UPDATE_ROLE,
                        rolePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("회차를 찾을 수 없으면 회차 루틴 실행 완료 API는 식별 가능한 404 오류를 반환한다")
    @Test
    void documentsSeasonRoundNotFound() throws Exception {
        when(useCase.updateRoutineExecutionCompletion(
                TEAM_ID,
                SEASON_ID,
                ROUND_ID,
                EXECUTION_ID,
                ACCESS_KEY,
                true
        )).thenThrow(new WorkspaceNotFoundException("SEASON_ROUND_NOT_FOUND", "회차를 찾을 수 없습니다"));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}"
                                + "/routine-executions/{executionId}/completion",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID,
                        EXECUTION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEASON_ROUND_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("회차를 찾을 수 없습니다"))
                .andDo(document(
                        "updateRoutineExecutionCompletionRoundNotFound",
                        UPDATE_ROUTINE_EXECUTION_COMPLETION,
                        routineExecutionPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("루틴 실행을 찾을 수 없으면 회차 루틴 실행 완료 API는 식별 가능한 404 오류를 반환한다")
    @Test
    void documentsRoutineExecutionNotFound() throws Exception {
        when(useCase.updateRoutineExecutionCompletion(
                TEAM_ID,
                SEASON_ID,
                ROUND_ID,
                EXECUTION_ID,
                ACCESS_KEY,
                true
        )).thenThrow(new WorkspaceNotFoundException(
                "ROUTINE_EXECUTION_NOT_FOUND",
                "루틴 실행 기록을 찾을 수 없습니다"
        ));

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}"
                                + "/routine-executions/{executionId}/completion",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID,
                        EXECUTION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTINE_EXECUTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("루틴 실행 기록을 찾을 수 없습니다"))
                .andDo(document(
                        "updateRoutineExecutionCompletionExecutionNotFound",
                        UPDATE_ROUTINE_EXECUTION_COMPLETION,
                        routineExecutionPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("회차 루틴 실행 완료 상태 변경이 다른 변경과 충돌하면 409 오류를 반환한다")
    @Test
    void documentsUpdateRoutineExecutionCompletionContentConflict() throws Exception {
        when(useCase.updateRoutineExecutionCompletion(
                TEAM_ID,
                SEASON_ID,
                ROUND_ID,
                EXECUTION_ID,
                ACCESS_KEY,
                true
        )).thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(patch(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}"
                                + "/routine-executions/{executionId}/completion",
                        TEAM_ID,
                        SEASON_ID,
                        ROUND_ID,
                        EXECUTION_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\": true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"))
                .andDo(document(
                        "updateRoutineExecutionCompletionContentConflict",
                        UPDATE_ROUTINE_EXECUTION_COMPLETION,
                        routineExecutionPathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("수정할 루틴이 없으면 루틴 수정 API는 식별 가능한 404 오류를 반환한다")
    @Test
    void documentsUpdateRoutineNotFound() throws Exception {
        when(useCase.updateRoutine(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROUTINE_ID),
                eq(ACCESS_KEY),
                any(UpdateRoutineCommand.class)
        ))
                .thenThrow(new WorkspaceNotFoundException("ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다"));

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROUTINE_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateRoutineRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTINE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("루틴을 찾을 수 없습니다"))
                .andDo(document(
                        "updateRoutineNotFound",
                        UPDATE_ROUTINE,
                        routinePathParameters(),
                        accessKeyHeader(),
                        responseFields(errorResponseFields())));
    }

    @DisplayName("루틴 수정이 다른 변경과 충돌하면 재시도를 안내하는 409 오류를 반환한다")
    @Test
    void documentsUpdateRoutineContentConflict() throws Exception {
        when(useCase.updateRoutine(
                eq(TEAM_ID),
                eq(SEASON_ID),
                eq(ROUTINE_ID),
                eq(ACCESS_KEY),
                any(UpdateRoutineCommand.class)
        ))
                .thenThrow(new WorkspaceContentConflictException());

        mockMvc.perform(put(
                        "/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}",
                        TEAM_ID,
                        SEASON_ID,
                        ROUTINE_ID)
                        .header("X-Baton-Access-Key", ACCESS_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateRoutineRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_CONTENT_CONFLICT"))
                .andDo(document(
                        "updateRoutineContentConflict",
                        UPDATE_ROUTINE,
                        routinePathParameters(),
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
                .andDo(document(
                        "createWorkspaceInvalidInput",
                        CREATE_WORKSPACE,
                        responseFields(errorResponseFields())));
    }

    @DisplayName("예상하지 못한 내부 오류는 안전한 500 오류 계약으로 반환한다")
    @Test
    void documentsUnexpectedInternalError() throws Exception {
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
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버에서 요청을 처리하지 못했습니다"))
                .andDo(document(
                        "createWorkspaceInternalError",
                        CREATE_WORKSPACE,
                        responseFields(errorResponseFields())));
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
                seasonResult(),
                List.of(new WorkspaceUseCase.SeasonSummaryResult(
                        SEASON_ID,
                        "2026 여름 시즌",
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 9, 17),
                        null,
                        null,
                        "Asia/Seoul",
                        roundScheduleResult()
                )),
                List.of(
                        new MemberResult(MEMBER_ID, "박민서", "박", "#d9e4da", null),
                        new MemberResult(NEXT_MEMBER_ID, "김준호", "김", "#f1d6cc", null)
                ),
                List.of(roleResult()),
                List.of(routineResult()),
                List.of(seasonRoundResult(RoutineStatus.WAITING)),
                List.of(decisionResult()),
                List.of(legacyHandoffItemResult()),
                List.of(legacyRoleResourceResult()),
                List.of(roleHandoffResult(RoleHandoffStatus.TRANSFERRED)),
                List.of(new ContinuitySignalResult(
                        ContinuitySignalType.HANDOFF_INCOMPLETE,
                        ContinuitySignalSeverity.WARNING,
                        ROLE_ID,
                        null,
                        "질문 큐레이터 바통 수락 대기",
                        "질문 큐레이터 역할의 새 담당 시작일이 2026-08-01입니다. "
                                + "전달 snapshot에 미완료 바통 항목이 1개 있고 "
                                + "아직 수락하지 않았습니다.",
                        "다음 담당자가 바통을 수락하고 남은 항목을 확인하세요.",
                        LocalDate.of(2026, 8, 1)
                ))
        );
    }

    private WorkspaceUseCase.SeasonResult seasonResult() {
        return new WorkspaceUseCase.SeasonResult(
                SEASON_ID,
                "2026 여름 시즌",
                LocalDate.of(2026, 7, 2),
                LocalDate.of(2026, 9, 17),
                null,
                null,
                "Asia/Seoul",
                roundScheduleResult()
        );
    }

    private WorkspaceUseCase.SeasonResult endedSeasonResult() {
        return new WorkspaceUseCase.SeasonResult(
                SEASON_ID,
                "2026 여름 시즌",
                LocalDate.of(2026, 7, 2),
                LocalDate.of(2026, 9, 17),
                Instant.parse("2026-09-18T00:00:00Z"),
                null,
                "Asia/Seoul",
                roundScheduleResult()
        );
    }

    private WorkspaceUseCase.NextSeasonResult nextSeasonResult() {
        return new WorkspaceUseCase.NextSeasonResult(
                endedSeasonResult(),
                new WorkspaceUseCase.SeasonResult(
                        NEXT_SEASON_ID,
                        "2026 가을 시즌",
                        LocalDate.of(2026, 9, 18),
                        LocalDate.of(2026, 12, 17),
                        null,
                        SEASON_ID,
                        "Asia/Seoul",
                        null
                ),
                List.of(new WorkspaceUseCase.CopiedRoleResult(ROLE_ID, COPIED_ROLE_ID)),
                List.of(new WorkspaceUseCase.CopiedRoutineResult(ROUTINE_ID, COPIED_ROUTINE_ID))
        );
    }

    private WorkspaceUseCase.RoundScheduleResult roundScheduleResult() {
        return new WorkspaceUseCase.RoundScheduleResult(
                "Asia/Seoul",
                LocalDate.of(2026, 8, 6),
                LocalTime.of(20, 0),
                RoundRecurrence.WEEKLY,
                7,
                true,
                LocalDate.of(2026, 8, 6)
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

    private String validRoutineRequest() {
        return """
                {
                  "title": "모임 전 질문 모으기",
                  "phase": "BEFORE",
                  "dueLabel": "모임 하루 전",
                  "deadlineDayOffset": -1,
                  "deadlineTime": "22:00",
                  "ownerRoleId": "44444444-4444-4444-4444-444444444444",
                  "detail": "공통 질문을 한 문서에 정리합니다"
                }
                """;
    }

    private String validUpdateSeasonRequest() {
        return """
                {
                  "name": "2026 여름 시즌",
                  "startDate": "2026-07-02",
                  "endDate": "2026-09-17"
                }
                """;
    }

    private String validRoundScheduleRequest() {
        return """
                {
                  "timeZone": "Asia/Seoul",
                  "firstMeetingDate": "2026-08-06",
                  "meetingTime": "20:00",
                  "recurrence": "WEEKLY",
                  "generationLeadDays": 7,
                  "enabled": true
                }
                """;
    }

    private String validCreateNextSeasonRequest() {
        return """
                {
                  "name": "2026 가을 시즌",
                  "startDate": "2026-09-18",
                  "endDate": "2026-12-17",
                  "copyRoleIds": ["44444444-4444-4444-4444-444444444444"],
                  "copyRoutineIds": ["55555555-5555-5555-5555-555555555555"]
                }
                """;
    }

    private String validUpdateRoleResourceRequest() {
        return """
                {
                  "roleId": "44444444-4444-4444-4444-444444444444",
                  "title": "질문 정리 가이드 개정판",
                  "url": "https://docs.example.com/question-guide-v2",
                  "description": "이번 시즌에 맞춘 질문 분류 기준"
                }
                """;
    }

    private String validUpdateRoleRequest() {
        return """
                {
                  "name": "회고 큐레이터",
                  "purpose": "회고를 모아 다음 모임의 실험으로 연결합니다",
                  "currentMemberId": "33333333-3333-3333-3333-444444444444",
                  "nextMemberId": "33333333-3333-3333-3333-333333333333",
                  "assignmentStartDate": "2026-07-27",
                  "assignmentEndDate": "2026-09-17",
                  "responsibilities": ["회고 수집", "다음 실험 정리"],
                  "risk": "회고가 실행 항목으로 이어지지 않을 수 있습니다"
                }
                """;
    }

    private String validUpdateRoutineRequest() {
        return """
                {
                  "title": "모임 후 회고 모으기",
                  "phase": "AFTER",
                  "dueLabel": "모임 다음 날",
                  "deadlineDayOffset": 1,
                  "deadlineTime": "18:00",
                  "ownerRoleId": "44444444-4444-4444-4444-444444444444",
                  "detail": "좋았던 점과 다음 실험을 한 문서에 정리합니다"
                }
                """;
    }

    private String validSeasonRoundRequest() {
        return """
                {
                  "name": "3회차",
                  "meetingDate": "2026-07-27"
                }
                """;
    }

    private String validUpdateSeasonRoundRequest() {
        return """
                {
                  "name": "세 번째 모임",
                  "meetingDate": "2026-07-28"
                }
                """;
    }

    private String validMemberRequest() {
        return """
                {
                  "name": "최유진"
                }
                """;
    }

    private String validUpdateDecisionRequest() {
        return """
                {
                  "title": "질문은 모임 전날 마감한다",
                  "reason": "진행자가 준비할 시간을 확보합니다",
                  "alternative": "모임 당일에도 받는 방안을 검토했습니다",
                  "authorMemberId": "33333333-3333-3333-3333-333333333333",
                  "roleIds": ["44444444-4444-4444-4444-444444444444"]
                }
                """;
    }

    private String validUpdateHandoffItemRequest() {
        return """
                {
                  "roleId": "44444444-4444-4444-4444-444444444444",
                  "label": "질문 목록 문서 권한 넘기기",
                  "category": "RESOURCE"
                }
                """;
    }

    private String validPrepareRoleHandoffRequest() {
        return """
                {
                  "toMemberId": "33333333-3333-3333-3333-444444444444",
                  "incomingAssignmentStartDate": "2026-08-01",
                  "incomingAssignmentEndDate": "2026-09-17"
                }
                """;
    }

    private String validConfirmRoleHandoffRequest() {
        return """
                {
                  "confirmedByMemberId": "33333333-3333-3333-3333-444444444444"
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

    private RoleResult updatedRoleResult() {
        return new RoleResult(
                ROLE_ID,
                "회고 큐레이터",
                "회고를 모아 다음 모임의 실험으로 연결합니다",
                NEXT_MEMBER_ID,
                MEMBER_ID,
                LocalDate.of(2026, 7, 27),
                LocalDate.of(2026, 9, 17),
                List.of("회고 수집", "다음 실험 정리"),
                "회고가 실행 항목으로 이어지지 않을 수 있습니다"
        );
    }

    private RoleHandoffTransitionResult roleHandoffTransitionResult(
            RoleHandoffStatus status
    ) {
        RoleResult role = switch (status) {
            case ACCEPTED -> new RoleResult(
                    ROLE_ID,
                    "질문 큐레이터",
                    "막힌 지점을 모아 함께 풉니다",
                    NEXT_MEMBER_ID,
                    null,
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 9, 17),
                    List.of("질문 수집", "공통 막힘 정리"),
                    "질문이 개인 메모에만 남을 수 있습니다"
            );
            case CANCELLED -> new RoleResult(
                    ROLE_ID,
                    "질문 큐레이터",
                    "막힌 지점을 모아 함께 풉니다",
                    MEMBER_ID,
                    null,
                    LocalDate.of(2026, 7, 20),
                    LocalDate.of(2026, 9, 17),
                    List.of("질문 수집", "공통 막힘 정리"),
                    "질문이 개인 메모에만 남을 수 있습니다"
            );
            default -> roleResult();
        };
        return new RoleHandoffTransitionResult(role, roleHandoffResult(status));
    }

    private RoleHandoffResult roleHandoffResult(RoleHandoffStatus status) {
        boolean transferred = status == RoleHandoffStatus.TRANSFERRED
                || status == RoleHandoffStatus.ACCEPTED;
        boolean accepted = status == RoleHandoffStatus.ACCEPTED;
        boolean cancelled = status == RoleHandoffStatus.CANCELLED;
        return new RoleHandoffResult(
                ROLE_HANDOFF_ID,
                ROLE_ID,
                MEMBER_ID,
                NEXT_MEMBER_ID,
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 9, 17),
                status,
                Instant.parse("2026-07-30T08:00:00Z"),
                transferred ? Instant.parse("2026-07-30T09:00:00Z") : null,
                accepted ? Instant.parse("2026-07-30T10:00:00Z") : null,
                cancelled ? Instant.parse("2026-07-30T09:30:00Z") : null,
                transferred ? MEMBER_ID : null,
                accepted ? NEXT_MEMBER_ID : null,
                cancelled ? MEMBER_ID : null,
                transferred ? 2 : null,
                transferred ? 1 : null,
                transferred ? 0 : null,
                transferred
        );
    }

    private RoutineResult routineResult() {
        return new RoutineResult(
                ROUTINE_ID,
                "모임 전 질문 모으기",
                RoutinePhase.BEFORE,
                "모임 하루 전",
                ROLE_ID,
                "공통 질문을 한 문서에 정리합니다",
                -1,
                LocalTime.of(22, 0)
        );
    }

    private RoutineResult updatedRoutineResult() {
        return new RoutineResult(
                ROUTINE_ID,
                "모임 후 회고 모으기",
                RoutinePhase.AFTER,
                "모임 다음 날",
                ROLE_ID,
                "좋았던 점과 다음 실험을 한 문서에 정리합니다",
                1,
                LocalTime.of(18, 0)
        );
    }

    private SeasonRoundResult seasonRoundResult(RoutineStatus status) {
        return new SeasonRoundResult(
                ROUND_ID,
                "3회차",
                LocalDate.of(2026, 7, 27),
                List.of(routineExecutionResult(status)),
                null,
                RoundOrigin.MANUAL,
                null,
                null,
                status == RoutineStatus.DONE
                        ? RoundTimingStatus.COMPLETED
                        : RoundTimingStatus.IN_PROGRESS
        );
    }

    private SeasonRoundResult updatedSeasonRoundResult(Instant archivedAt) {
        return new SeasonRoundResult(
                ROUND_ID,
                "세 번째 모임",
                LocalDate.of(2026, 7, 28),
                List.of(routineExecutionResult(RoutineStatus.WAITING)),
                archivedAt,
                RoundOrigin.MANUAL,
                null,
                null,
                RoundTimingStatus.PLANNED
        );
    }

    private RoutineExecutionResult routineExecutionResult(RoutineStatus status) {
        return new RoutineExecutionResult(
                EXECUTION_ID,
                ROUND_ID,
                ROUTINE_ID,
                "모임 전 질문 모으기",
                RoutinePhase.BEFORE,
                "모임 하루 전",
                ROLE_ID,
                status,
                "공통 질문을 한 문서에 정리합니다",
                Instant.parse("2026-07-26T13:00:00Z"),
                status == RoutineStatus.DONE
                        ? RoutineTimingStatus.COMPLETED
                        : RoutineTimingStatus.IN_PROGRESS
        );
    }

    private DecisionResult decisionResult() {
        return decisionResult(null);
    }

    private DecisionResult decisionResult(Instant archivedAt) {
        return new DecisionResult(
                DECISION_ID,
                "질문은 모임 전날 마감한다",
                "진행자가 준비할 시간을 확보합니다",
                "모임 당일에도 받는 방안을 검토했습니다",
                Instant.parse("2026-07-20T03:04:05Z"),
                MEMBER_ID,
                "박민서",
                List.of(ROLE_ID),
                archivedAt
        );
    }

    private HandoffItemResult handoffItemResult(boolean completed) {
        return handoffItemResult(completed, null);
    }

    private HandoffItemResult handoffItemResult(boolean completed, Instant archivedAt) {
        return new HandoffItemResult(
                HANDOFF_ITEM_ID,
                ROLE_ID,
                "질문 목록 문서 권한 넘기기",
                HandoffCategory.RESOURCE,
                completed,
                Instant.parse("2026-07-20T03:04:05Z"),
                archivedAt
        );
    }

    private HandoffItemResult legacyHandoffItemResult() {
        return new HandoffItemResult(
                HANDOFF_ITEM_ID,
                ROLE_ID,
                "질문 목록 문서 권한 넘기기",
                HandoffCategory.RESOURCE,
                false,
                null,
                null
        );
    }

    private RoleResourceResult roleResourceResult() {
        return new RoleResourceResult(
                ROLE_RESOURCE_ID,
                ROLE_ID,
                "질문 정리 가이드",
                "https://docs.example.com/question-guide",
                "질문을 모으고 분류하는 기준",
                Instant.parse("2026-07-20T03:04:05Z")
        );
    }

    private RoleResourceResult legacyRoleResourceResult() {
        return new RoleResourceResult(
                ROLE_RESOURCE_ID,
                ROLE_ID,
                "질문 정리 가이드",
                "https://docs.example.com/question-guide",
                "질문을 모으고 분류하는 기준",
                null
        );
    }

    private RoleResourceResult updatedRoleResourceResult() {
        return new RoleResourceResult(
                ROLE_RESOURCE_ID,
                ROLE_ID,
                "질문 정리 가이드 개정판",
                "https://docs.example.com/question-guide-v2",
                "이번 시즌에 맞춘 질문 분류 기준",
                Instant.parse("2026-07-20T03:04:05Z")
        );
    }

    private RestDocumentationResultHandler document(
            String resourceIdentifier,
            OperationDocumentation operation,
            Snippet... snippets
    ) {
        List<Snippet> completeSnippets = new ArrayList<>(Arrays.asList(snippets));
        if (completeSnippets.stream().noneMatch(ResponseHeadersSnippet.class::isInstance)) {
            completeSnippets.add(requestIdResponseHeader());
        }
        return MockMvcRestDocumentationWrapper.document(
                resourceIdentifier,
                operation.description(),
                operation.summary(),
                completeSnippets.toArray(Snippet[]::new)
        );
    }

    private Snippet workspacePathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("팀 UUID"),
                parameterWithName("seasonId").description("시즌 UUID")
        );
    }

    private Snippet rolePathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("팀 UUID"),
                parameterWithName("seasonId").description("시즌 UUID"),
                parameterWithName("roleId").description("역할 UUID")
        );
    }

    private Snippet roleHandoffPathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("팀 UUID"),
                parameterWithName("seasonId").description("시즌 UUID"),
                parameterWithName("roleId").description("역할 UUID"),
                parameterWithName("handoffId").description("역할 바통 UUID")
        );
    }

    private Snippet memberPathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("팀 UUID"),
                parameterWithName("seasonId").description("시즌 UUID"),
                parameterWithName("memberId").description("구성원 UUID")
        );
    }

    private Snippet routinePathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("팀 UUID"),
                parameterWithName("seasonId").description("시즌 UUID"),
                parameterWithName("routineId").description("루틴 UUID")
        );
    }

    private Snippet decisionPathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("팀 UUID"),
                parameterWithName("seasonId").description("시즌 UUID"),
                parameterWithName("decisionId").description("결정 UUID")
        );
    }

    private Snippet handoffItemPathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("팀 UUID"),
                parameterWithName("seasonId").description("시즌 UUID"),
                parameterWithName("itemId").description("인수인계 항목 UUID")
        );
    }

    private Snippet roleResourcePathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("팀 UUID"),
                parameterWithName("seasonId").description("시즌 UUID"),
                parameterWithName("resourceId").description("역할 자료 UUID")
        );
    }

    private Snippet routineExecutionPathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("팀 UUID"),
                parameterWithName("seasonId").description("시즌 UUID"),
                parameterWithName("roundId").description("시즌 회차 UUID"),
                parameterWithName("executionId").description("회차 루틴 실행 UUID")
        );
    }

    private Snippet seasonRoundPathParameters() {
        return pathParameters(
                parameterWithName("teamId").description("팀 UUID"),
                parameterWithName("seasonId").description("시즌 UUID"),
                parameterWithName("roundId").description("시즌 회차 UUID")
        );
    }

    private Snippet accessKeyHeader() {
        return requestHeaders(headerWithName("X-Baton-Access-Key").description("워크스페이스 접근 키"));
    }

    private Snippet contentCreationHeaders() {
        return requestHeaders(
                headerWithName("Idempotency-Key")
                        .description("같은 생성 요청을 안전하게 재시도할 32~200자의 URL 안전 멱등 키"),
                headerWithName("X-Baton-Access-Key").description("워크스페이스 접근 키")
        );
    }

    private Snippet noStoreResponseHeader() {
        return responseHeadersWithRequestId(
                headerWithName("Cache-Control")
                        .description("민감한 응답을 저장하지 않도록 하는 no-store 지시자")
        );
    }

    private Snippet requestIdResponseHeader() {
        return responseHeadersWithRequestId();
    }

    private Snippet responseHeadersWithRequestId(HeaderDescriptor... descriptors) {
        List<HeaderDescriptor> completeDescriptors = new ArrayList<>();
        completeDescriptors.add(headerWithName(RequestIdFilter.HEADER_NAME)
                .description("서버가 생성한 불투명 요청 진단 식별자"));
        completeDescriptors.addAll(Arrays.asList(descriptors));
        return responseHeaders(completeDescriptors.toArray(HeaderDescriptor[]::new));
    }

    private FieldDescriptor[] workspaceResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("team").type(JsonFieldType.OBJECT).description("팀 정보"),
                fieldWithPath("team.id").description("팀 UUID"),
                fieldWithPath("team.name").description("팀 이름"),
                fieldWithPath("season").type(JsonFieldType.OBJECT).description("현재 시즌 정보"),
                fieldWithPath("season.id").description("시즌 UUID"),
                fieldWithPath("season.name").description("시즌 이름"),
                fieldWithPath("season.startDate").description("시즌 시작일"),
                fieldWithPath("season.endDate").description("시즌 종료일"),
                fieldWithPath("season.endedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("명시적으로 종료한 UTC 시각"),
                fieldWithPath("season.previousSeasonId")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("이 시즌을 시작한 원본 시즌 UUID"),
                fieldWithPath("season.timeZone").description("시즌의 IANA 시간대 식별자"),
                fieldWithPath("season.roundSchedule")
                        .type(JsonFieldType.OBJECT)
                        .optional()
                        .description("시즌당 하나인 자동 회차 일정"),
                fieldWithPath("season.roundSchedule.firstMeetingDate")
                        .optional()
                        .description("첫 자동 회차 날짜"),
                fieldWithPath("season.roundSchedule.meetingTime")
                        .optional()
                        .description("시즌 시간대 기준 모임 시각"),
                enumField(RoundRecurrence.class,
                        "season.roundSchedule.recurrence",
                        "주간 또는 격주 반복 주기").optional(),
                fieldWithPath("season.roundSchedule.generationLeadDays")
                        .optional()
                        .description("회차 선행 생성 기간"),
                fieldWithPath("season.roundSchedule.enabled")
                        .optional()
                        .description("자동 생성 활성 여부"),
                fieldWithPath("season.roundSchedule.nextOccurrenceDate")
                        .optional()
                        .description("다음 생성 대상 발생일"),
                fieldWithPath("seasons").type(JsonFieldType.ARRAY).description("팀의 서버 권위 시즌 목록"),
                fieldWithPath("seasons[].id").description("시즌 UUID"),
                fieldWithPath("seasons[].name").description("시즌 이름"),
                fieldWithPath("seasons[].startDate").description("시즌 시작일"),
                fieldWithPath("seasons[].endDate").description("시즌 종료일"),
                fieldWithPath("seasons[].endedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("명시적으로 종료한 UTC 시각"),
                fieldWithPath("seasons[].previousSeasonId")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("이 시즌을 시작한 원본 시즌 UUID"),
                fieldWithPath("seasons[].timeZone").description("시즌의 IANA 시간대 식별자"),
                fieldWithPath("seasons[].roundSchedule")
                        .type(JsonFieldType.OBJECT)
                        .optional()
                        .description("시즌당 하나인 자동 회차 일정"),
                fieldWithPath("seasons[].roundSchedule.firstMeetingDate")
                        .optional()
                        .description("첫 자동 회차 날짜"),
                fieldWithPath("seasons[].roundSchedule.meetingTime")
                        .optional()
                        .description("시즌 시간대 기준 모임 시각"),
                enumField(RoundRecurrence.class,
                        "seasons[].roundSchedule.recurrence",
                        "주간 또는 격주 반복 주기").optional(),
                fieldWithPath("seasons[].roundSchedule.generationLeadDays")
                        .optional()
                        .description("회차 선행 생성 기간"),
                fieldWithPath("seasons[].roundSchedule.enabled")
                        .optional()
                        .description("자동 생성 활성 여부"),
                fieldWithPath("seasons[].roundSchedule.nextOccurrenceDate")
                        .optional()
                        .description("다음 생성 대상 발생일"),
                fieldWithPath("members").type(JsonFieldType.ARRAY).description("팀 구성원 목록"),
                fieldWithPath("members[].id").description("구성원 UUID"),
                fieldWithPath("members[].name").description("구성원 이름"),
                fieldWithPath("members[].initials").description("표시용 이니셜"),
                fieldWithPath("members[].tone").description("표시용 색상"),
                fieldWithPath("members[].deactivatedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("활동 종료 UTC 시각"),
                fieldWithPath("roles").type(JsonFieldType.ARRAY).description("역할 목록"),
                fieldWithPath("roles[].id").description("역할 UUID"),
                fieldWithPath("roles[].name").description("역할 이름"),
                fieldWithPath("roles[].purpose").description("역할 목적"),
                fieldWithPath("roles[].currentMemberId").optional().description("현재 담당자 UUID"),
                fieldWithPath("roles[].nextMemberId").optional().description("다음 담당자 UUID"),
                fieldWithPath("roles[].assignmentStartDate").optional().description("배정 시작일"),
                fieldWithPath("roles[].assignmentEndDate").optional().description("배정 종료일"),
                stringArrayField("roles[].responsibilities[]", "역할 책임 목록"),
                fieldWithPath("roles[].risk").optional().description("위험 신호"),
                fieldWithPath("routines").type(JsonFieldType.ARRAY).description("루틴 목록"),
                fieldWithPath("routines[].id").description("루틴 UUID"),
                fieldWithPath("routines[].title").description("루틴 제목"),
                enumField(RoutinePhase.class, "routines[].phase", "실행 단계"),
                fieldWithPath("routines[].dueLabel").description("기한 문구"),
                fieldWithPath("routines[].ownerRoleId").description("담당 역할 UUID"),
                fieldWithPath("routines[].detail").description("루틴 상세"),
                fieldWithPath("routines[].deadlineDayOffset")
                        .optional()
                        .description("모임 날짜 기준 마감일 오프셋"),
                fieldWithPath("routines[].deadlineTime")
                        .optional()
                        .description("시즌 시간대 기준 마감 시각"),
                fieldWithPath("rounds").type(JsonFieldType.ARRAY).description("시즌 회차 목록"),
                fieldWithPath("rounds[].id").description("시즌 회차 UUID"),
                fieldWithPath("rounds[].name").description("시즌 안에서 유일한 회차 이름"),
                fieldWithPath("rounds[].meetingDate").optional().description("모임 날짜"),
                fieldWithPath("rounds[].archivedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("보관한 UTC 시각"),
                enumField(RoundOrigin.class, "rounds[].origin", "수동 또는 자동 생성 출처"),
                fieldWithPath("rounds[].scheduledOccurrenceDate")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("자동 일정의 원래 발생일"),
                fieldWithPath("rounds[].scheduledAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("자동 일정의 원래 UTC 모임 시각"),
                enumField(RoundTimingStatus.class,
                        "rounds[].timingStatus",
                        "회차의 예정, 진행, 지연 또는 완료 상태"),
                fieldWithPath("rounds[].routineExecutions")
                        .type(JsonFieldType.ARRAY)
                        .description("회차를 만들 때 복제한 루틴 실행 목록"),
                fieldWithPath("rounds[].routineExecutions[].id").description("회차 루틴 실행 UUID"),
                fieldWithPath("rounds[].routineExecutions[].roundId").description("소속 회차 UUID"),
                fieldWithPath("rounds[].routineExecutions[].routineId").description("원본 루틴 정의 UUID"),
                fieldWithPath("rounds[].routineExecutions[].title").description("회차 생성 시점의 루틴 제목"),
                enumField(RoutinePhase.class, "rounds[].routineExecutions[].phase", "회차 생성 시점의 실행 단계"),
                fieldWithPath("rounds[].routineExecutions[].dueLabel").description("회차 생성 시점의 기한 문구"),
                fieldWithPath("rounds[].routineExecutions[].ownerRoleId").description("회차 생성 시점의 담당 역할 UUID"),
                enumField(RoutineStatus.class, "rounds[].routineExecutions[].status", "WAITING 또는 DONE"),
                fieldWithPath("rounds[].routineExecutions[].detail").description("회차 생성 시점의 실행 방법"),
                fieldWithPath("rounds[].routineExecutions[].deadlineAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("회차 생성 시 고정한 UTC 실제 마감"),
                enumField(RoutineTimingStatus.class,
                        "rounds[].routineExecutions[].timingStatus",
                        "실행의 미설정, 예정, 진행, 지연 또는 완료 상태"),
                fieldWithPath("decisions").type(JsonFieldType.ARRAY).description("결정 기록 목록"),
                fieldWithPath("decisions[].id").description("결정 UUID"),
                fieldWithPath("decisions[].title").description("결정 제목"),
                fieldWithPath("decisions[].reason").description("결정 이유"),
                fieldWithPath("decisions[].alternative").description("검토한 대안"),
                fieldWithPath("decisions[].createdAt").description("서버가 기록한 UTC 시각"),
                fieldWithPath("decisions[].authorMemberId").description("작성자 구성원 UUID"),
                fieldWithPath("decisions[].authorName").description("작성자 이름"),
                stringArrayField("decisions[].roleIds[]", "관련 역할 UUID 목록"),
                fieldWithPath("decisions[].archivedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("보관한 UTC 시각"),
                fieldWithPath("handoffItems").type(JsonFieldType.ARRAY).description("인수인계 항목 목록"),
                fieldWithPath("handoffItems[].id").description("인수인계 항목 UUID"),
                fieldWithPath("handoffItems[].roleId").description("소유 역할 UUID"),
                fieldWithPath("handoffItems[].label").description("항목 내용"),
                enumField(HandoffCategory.class, "handoffItems[].category", "항목 분류"),
                fieldWithPath("handoffItems[].completed").description("완료 여부"),
                fieldWithPath("handoffItems[].createdAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("서버가 기록한 UTC 생성 시각. V14 이전 기록은 null"),
                fieldWithPath("handoffItems[].archivedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("보관한 UTC 시각"),
                fieldWithPath("resources").type(JsonFieldType.ARRAY).description("역할별 참고 자료 목록"),
                fieldWithPath("resources[].id").description("자료 UUID"),
                fieldWithPath("resources[].roleId").description("소유 역할 UUID"),
                fieldWithPath("resources[].title").description("자료 제목"),
                fieldWithPath("resources[].url").description("http 또는 https 외부 링크"),
                fieldWithPath("resources[].description").optional().description("자료 사용 맥락"),
                fieldWithPath("resources[].createdAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("서버가 기록한 UTC 생성 시각. V14 이전 기록은 null"),
                fieldWithPath("roleHandoffs")
                        .type(JsonFieldType.ARRAY)
                        .description("역할별 바통 준비·전달·수락·취소 이력"),
                fieldWithPath("roleHandoffs[].id").description("역할 바통 UUID"),
                fieldWithPath("roleHandoffs[].roleId").description("대상 역할 UUID"),
                fieldWithPath("roleHandoffs[].fromMemberId").description("이전 담당자 UUID"),
                fieldWithPath("roleHandoffs[].toMemberId").description("다음 담당자 UUID"),
                fieldWithPath("roleHandoffs[].outgoingAssignmentStartDate")
                        .description("준비 시점의 이전 담당 시작일"),
                fieldWithPath("roleHandoffs[].outgoingAssignmentEndDate")
                        .optional()
                        .description("준비 시점의 이전 담당 종료일"),
                fieldWithPath("roleHandoffs[].incomingAssignmentStartDate")
                        .description("수락 뒤 적용할 다음 담당 시작일"),
                fieldWithPath("roleHandoffs[].incomingAssignmentEndDate")
                        .optional()
                        .description("수락 뒤 적용할 다음 담당 종료일"),
                enumField(
                        RoleHandoffStatus.class,
                        "roleHandoffs[].status",
                        "PREPARING, TRANSFERRED, ACCEPTED 또는 CANCELLED"
                ),
                fieldWithPath("roleHandoffs[].preparedAt").description("준비한 UTC 시각"),
                fieldWithPath("roleHandoffs[].transferredAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("전달한 UTC 시각"),
                fieldWithPath("roleHandoffs[].acceptedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("수락한 UTC 시각"),
                fieldWithPath("roleHandoffs[].cancelledAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("취소한 UTC 시각"),
                fieldWithPath("roleHandoffs[].transferredByMemberId")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("전달을 확인했다고 선언한 구성원 UUID"),
                fieldWithPath("roleHandoffs[].acceptedByMemberId")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("수락을 확인했다고 선언한 구성원 UUID"),
                fieldWithPath("roleHandoffs[].cancelledByMemberId")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("취소를 확인했다고 선언한 구성원 UUID"),
                fieldWithPath("roleHandoffs[].activeItemCount")
                        .type(JsonFieldType.NUMBER)
                        .optional()
                        .description("전달 시점의 활성 인수인계 항목 수"),
                fieldWithPath("roleHandoffs[].incompleteItemCount")
                        .type(JsonFieldType.NUMBER)
                        .optional()
                        .description("전달 시점의 미완료 항목 수"),
                fieldWithPath("roleHandoffs[].resourceCount")
                        .type(JsonFieldType.NUMBER)
                        .optional()
                        .description("전달 시점의 역할 자료 수"),
                fieldWithPath("roleHandoffs[].warningAcknowledged")
                        .description("준비도 경고를 명시적으로 확인했는지 여부"),
                fieldWithPath("continuitySignals")
                        .type(JsonFieldType.ARRAY)
                        .description("설명 가능한 규칙으로 계산한 조직 연속성 위험 신호"),
                enumField(
                        ContinuitySignalType.class,
                        "continuitySignals[].type",
                        "역할 공백, 후임 공백, 준비 부족, 반복 지연 또는 미완료 바통 유형"
                ),
                enumField(
                        ContinuitySignalSeverity.class,
                        "continuitySignals[].severity",
                        "CRITICAL 또는 WARNING 우선순위"
                ),
                fieldWithPath("continuitySignals[].roleId")
                        .description("신호가 가리키는 역할 UUID"),
                fieldWithPath("continuitySignals[].routineId")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("반복 지연 신호가 가리키는 루틴 UUID"),
                fieldWithPath("continuitySignals[].title")
                        .description("신호의 짧은 제목"),
                fieldWithPath("continuitySignals[].reason")
                        .description("현재 기록에서 이 신호가 발생한 이유"),
                fieldWithPath("continuitySignals[].recommendedAction")
                        .description("사용자가 바로 취할 수 있는 다음 행동"),
                fieldWithPath("continuitySignals[].relevantDate")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("담당 종료일 또는 새 담당 시작일 같은 관련 날짜")
        };
    }

    private FieldDescriptor[] seasonResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("id").description("시즌 UUID"),
                fieldWithPath("name").description("시즌 이름"),
                fieldWithPath("startDate").description("시즌 시작일"),
                fieldWithPath("endDate").description("시즌 종료일"),
                fieldWithPath("endedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("명시적으로 종료한 UTC 시각"),
                fieldWithPath("previousSeasonId")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("이 시즌을 시작한 원본 시즌 UUID"),
                fieldWithPath("timeZone").description("시즌의 IANA 시간대 식별자"),
                fieldWithPath("roundSchedule")
                        .type(JsonFieldType.OBJECT)
                        .optional()
                        .description("시즌당 하나인 자동 회차 일정"),
                fieldWithPath("roundSchedule.firstMeetingDate")
                        .optional()
                        .description("첫 자동 회차 날짜"),
                fieldWithPath("roundSchedule.meetingTime")
                        .optional()
                        .description("시즌 시간대 기준 모임 시각"),
                enumField(RoundRecurrence.class,
                        "roundSchedule.recurrence",
                        "주간 또는 격주 반복 주기").optional(),
                fieldWithPath("roundSchedule.generationLeadDays")
                        .optional()
                        .description("회차 선행 생성 기간"),
                fieldWithPath("roundSchedule.enabled")
                        .optional()
                        .description("자동 생성 활성 여부"),
                fieldWithPath("roundSchedule.nextOccurrenceDate")
                        .optional()
                        .description("다음 생성 대상 발생일")
        };
    }

    private FieldDescriptor[] nextSeasonResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("sourceSeason").type(JsonFieldType.OBJECT).description("종료한 원본 시즌"),
                fieldWithPath("sourceSeason.id").description("원본 시즌 UUID"),
                fieldWithPath("sourceSeason.name").description("원본 시즌 이름"),
                fieldWithPath("sourceSeason.startDate").description("원본 시즌 시작일"),
                fieldWithPath("sourceSeason.endDate").description("원본 시즌 종료일"),
                fieldWithPath("sourceSeason.endedAt").description("원본 시즌을 종료한 UTC 시각"),
                fieldWithPath("sourceSeason.previousSeasonId")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("원본 시즌의 이전 시즌 UUID"),
                fieldWithPath("sourceSeason.timeZone").description("원본 시즌 IANA 시간대"),
                fieldWithPath("sourceSeason.roundSchedule")
                        .type(JsonFieldType.OBJECT)
                        .optional()
                        .description("원본 시즌의 자동 회차 일정"),
                fieldWithPath("sourceSeason.roundSchedule.firstMeetingDate").optional()
                        .description("첫 자동 회차 날짜"),
                fieldWithPath("sourceSeason.roundSchedule.meetingTime").optional()
                        .description("시즌 시간대 기준 모임 시각"),
                enumField(RoundRecurrence.class,
                        "sourceSeason.roundSchedule.recurrence",
                        "주간 또는 격주 반복 주기").optional(),
                fieldWithPath("sourceSeason.roundSchedule.generationLeadDays").optional()
                        .description("회차 선행 생성 기간"),
                fieldWithPath("sourceSeason.roundSchedule.enabled").optional()
                        .description("자동 생성 활성 여부"),
                fieldWithPath("sourceSeason.roundSchedule.nextOccurrenceDate").optional()
                        .description("다음 생성 대상 발생일"),
                fieldWithPath("season").type(JsonFieldType.OBJECT).description("생성한 다음 시즌"),
                fieldWithPath("season.id").description("다음 시즌 UUID"),
                fieldWithPath("season.name").description("다음 시즌 이름"),
                fieldWithPath("season.startDate").description("다음 시즌 시작일"),
                fieldWithPath("season.endDate").description("다음 시즌 종료일"),
                fieldWithPath("season.endedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("다음 시즌 종료 UTC 시각"),
                fieldWithPath("season.previousSeasonId").description("원본 시즌 UUID"),
                fieldWithPath("season.timeZone").description("다음 시즌 IANA 시간대"),
                fieldWithPath("season.roundSchedule")
                        .type(JsonFieldType.OBJECT)
                        .optional()
                        .description("새 시즌에서는 다시 확인할 자동 회차 일정"),
                fieldWithPath("season.roundSchedule.firstMeetingDate")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("첫 자동 회차 날짜"),
                fieldWithPath("season.roundSchedule.meetingTime")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("시즌 시간대 기준 모임 시각"),
                enumField(RoundRecurrence.class,
                        "season.roundSchedule.recurrence",
                        "주간 또는 격주 반복 주기").optional(),
                fieldWithPath("season.roundSchedule.generationLeadDays")
                        .type(JsonFieldType.NUMBER)
                        .optional()
                        .description("회차 선행 생성 기간"),
                fieldWithPath("season.roundSchedule.enabled")
                        .type(JsonFieldType.BOOLEAN)
                        .optional()
                        .description("자동 생성 활성 여부"),
                fieldWithPath("season.roundSchedule.nextOccurrenceDate")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("다음 생성 대상 발생일"),
                fieldWithPath("copiedRoles").type(JsonFieldType.ARRAY).description("복사한 역할 식별자 대응"),
                fieldWithPath("copiedRoles[].sourceRoleId").description("원본 역할 UUID"),
                fieldWithPath("copiedRoles[].roleId").description("새 역할 UUID"),
                fieldWithPath("copiedRoutines")
                        .type(JsonFieldType.ARRAY)
                        .description("복사한 루틴 식별자 대응"),
                fieldWithPath("copiedRoutines[].sourceRoutineId").description("원본 루틴 UUID"),
                fieldWithPath("copiedRoutines[].routineId").description("새 루틴 UUID")
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
                stringArrayField("responsibilities[]", "역할 책임 목록"),
                fieldWithPath("risk").optional().description("위험 신호")
        };
    }

    private FieldDescriptor[] roleHandoffTransitionResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("role").type(JsonFieldType.OBJECT).description("전이 뒤 역할"),
                fieldWithPath("role.id").description("역할 UUID"),
                fieldWithPath("role.name").description("역할 이름"),
                fieldWithPath("role.purpose").description("역할 목적"),
                fieldWithPath("role.currentMemberId").optional().description("현재 담당자 UUID"),
                fieldWithPath("role.nextMemberId").optional().description("다음 담당자 UUID"),
                fieldWithPath("role.assignmentStartDate").optional().description("배정 시작일"),
                fieldWithPath("role.assignmentEndDate").optional().description("배정 종료일"),
                stringArrayField("role.responsibilities[]", "역할 책임 목록"),
                fieldWithPath("role.risk").optional().description("위험 신호"),
                fieldWithPath("handoff").type(JsonFieldType.OBJECT).description("전이 뒤 역할 바통"),
                fieldWithPath("handoff.id").description("역할 바통 UUID"),
                fieldWithPath("handoff.roleId").description("대상 역할 UUID"),
                fieldWithPath("handoff.fromMemberId").description("이전 담당자 UUID"),
                fieldWithPath("handoff.toMemberId").description("다음 담당자 UUID"),
                fieldWithPath("handoff.outgoingAssignmentStartDate")
                        .description("준비 시점의 이전 담당 시작일"),
                fieldWithPath("handoff.outgoingAssignmentEndDate")
                        .optional()
                        .description("준비 시점의 이전 담당 종료일"),
                fieldWithPath("handoff.incomingAssignmentStartDate")
                        .description("수락 뒤 적용할 다음 담당 시작일"),
                fieldWithPath("handoff.incomingAssignmentEndDate")
                        .optional()
                        .description("수락 뒤 적용할 다음 담당 종료일"),
                enumField(
                        RoleHandoffStatus.class,
                        "handoff.status",
                        "PREPARING, TRANSFERRED, ACCEPTED 또는 CANCELLED"
                ),
                fieldWithPath("handoff.preparedAt").description("준비한 UTC 시각"),
                fieldWithPath("handoff.transferredAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("전달한 UTC 시각"),
                fieldWithPath("handoff.acceptedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("수락한 UTC 시각"),
                fieldWithPath("handoff.cancelledAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("취소한 UTC 시각"),
                fieldWithPath("handoff.transferredByMemberId")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("전달을 확인했다고 선언한 구성원 UUID"),
                fieldWithPath("handoff.acceptedByMemberId")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("수락을 확인했다고 선언한 구성원 UUID"),
                fieldWithPath("handoff.cancelledByMemberId")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("취소를 확인했다고 선언한 구성원 UUID"),
                fieldWithPath("handoff.activeItemCount")
                        .type(JsonFieldType.NUMBER)
                        .optional()
                        .description("전달 시점의 활성 인수인계 항목 수"),
                fieldWithPath("handoff.incompleteItemCount")
                        .type(JsonFieldType.NUMBER)
                        .optional()
                        .description("전달 시점의 미완료 항목 수"),
                fieldWithPath("handoff.resourceCount")
                        .type(JsonFieldType.NUMBER)
                        .optional()
                        .description("전달 시점의 역할 자료 수"),
                fieldWithPath("handoff.warningAcknowledged")
                        .description("준비도 경고를 명시적으로 확인했는지 여부")
        };
    }

    private FieldDescriptor[] routineResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("id").description("루틴 UUID"),
                fieldWithPath("title").description("루틴 제목"),
                enumField(RoutinePhase.class, "phase", "실행 단계"),
                fieldWithPath("dueLabel").description("기한 문구"),
                fieldWithPath("ownerRoleId").description("담당 역할 UUID"),
                fieldWithPath("detail").description("실행 방법"),
                fieldWithPath("deadlineDayOffset")
                        .optional()
                        .description("모임 날짜 기준 마감일 오프셋"),
                fieldWithPath("deadlineTime")
                        .optional()
                        .description("시즌 시간대 기준 마감 시각")
        };
    }

    private FieldDescriptor[] seasonRoundResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("id").description("시즌 회차 UUID"),
                fieldWithPath("name").description("시즌 안에서 유일한 회차 이름"),
                fieldWithPath("meetingDate").optional().description("모임 날짜"),
                fieldWithPath("routineExecutions").type(JsonFieldType.ARRAY).description("회차 루틴 실행 목록"),
                fieldWithPath("routineExecutions[].id").description("회차 루틴 실행 UUID"),
                fieldWithPath("routineExecutions[].roundId").description("소속 회차 UUID"),
                fieldWithPath("routineExecutions[].routineId").description("원본 루틴 정의 UUID"),
                fieldWithPath("routineExecutions[].title").description("회차 생성 시점의 루틴 제목"),
                enumField(RoutinePhase.class, "routineExecutions[].phase", "회차 생성 시점의 실행 단계"),
                fieldWithPath("routineExecutions[].dueLabel").description("회차 생성 시점의 기한 문구"),
                fieldWithPath("routineExecutions[].ownerRoleId").description("회차 생성 시점의 담당 역할 UUID"),
                enumField(RoutineStatus.class, "routineExecutions[].status", "WAITING 또는 DONE"),
                fieldWithPath("routineExecutions[].detail").description("회차 생성 시점의 실행 방법"),
                fieldWithPath("routineExecutions[].deadlineAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("회차 생성 시 고정한 UTC 실제 마감"),
                enumField(RoutineTimingStatus.class,
                        "routineExecutions[].timingStatus",
                        "실행의 미설정, 예정, 진행, 지연 또는 완료 상태"),
                fieldWithPath("archivedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("보관한 UTC 시각"),
                enumField(RoundOrigin.class, "origin", "수동 또는 자동 생성 출처"),
                fieldWithPath("scheduledOccurrenceDate")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("자동 일정의 원래 발생일"),
                fieldWithPath("scheduledAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("자동 일정의 원래 UTC 모임 시각"),
                enumField(RoundTimingStatus.class,
                        "timingStatus",
                        "회차의 예정, 진행, 지연 또는 완료 상태")
        };
    }

    private FieldDescriptor[] routineExecutionResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("id").description("회차 루틴 실행 UUID"),
                fieldWithPath("roundId").description("소속 회차 UUID"),
                fieldWithPath("routineId").description("원본 루틴 정의 UUID"),
                fieldWithPath("title").description("회차 생성 시점의 루틴 제목"),
                enumField(RoutinePhase.class, "phase", "회차 생성 시점의 실행 단계"),
                fieldWithPath("dueLabel").description("회차 생성 시점의 기한 문구"),
                fieldWithPath("ownerRoleId").description("회차 생성 시점의 담당 역할 UUID"),
                enumField(RoutineStatus.class, "status", "WAITING 또는 DONE"),
                fieldWithPath("detail").description("회차 생성 시점의 실행 방법"),
                fieldWithPath("deadlineAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("회차 생성 시 고정한 UTC 실제 마감"),
                enumField(RoutineTimingStatus.class,
                        "timingStatus",
                        "실행의 미설정, 예정, 진행, 지연 또는 완료 상태")
        };
    }

    private FieldDescriptor[] decisionResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("id").description("결정 UUID"),
                fieldWithPath("title").description("결정 제목"),
                fieldWithPath("reason").description("결정 이유"),
                fieldWithPath("alternative").description("검토한 대안"),
                fieldWithPath("createdAt").description("서버가 기록한 UTC 시각"),
                fieldWithPath("authorMemberId").description("작성자 구성원 UUID"),
                fieldWithPath("authorName").description("작성자 이름"),
                stringArrayField("roleIds[]", "관련 역할 UUID 목록"),
                fieldWithPath("archivedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("보관한 UTC 시각")
        };
    }

    private FieldDescriptor[] handoffItemResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("id").description("인수인계 항목 UUID"),
                fieldWithPath("roleId").description("소유 역할 UUID"),
                fieldWithPath("label").description("항목 내용"),
                enumField(HandoffCategory.class, "category", "항목 분류"),
                fieldWithPath("completed").description("완료 여부"),
                fieldWithPath("createdAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("서버가 기록한 UTC 생성 시각. V14 이전 기록은 null"),
                fieldWithPath("archivedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("보관한 UTC 시각")
        };
    }

    private FieldDescriptor[] memberResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("id").description("구성원 UUID"),
                fieldWithPath("name").description("구성원 이름"),
                fieldWithPath("initials").description("표시용 이니셜"),
                fieldWithPath("tone").description("표시용 색상"),
                fieldWithPath("deactivatedAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("활동 종료 UTC 시각")
        };
    }

    private FieldDescriptor[] roleResourceResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("id").description("자료 UUID"),
                fieldWithPath("roleId").description("소유 역할 UUID"),
                fieldWithPath("title").description("자료 제목"),
                fieldWithPath("url").description("http 또는 https 외부 링크"),
                fieldWithPath("description").optional().description("자료 사용 맥락"),
                fieldWithPath("createdAt")
                        .type(JsonFieldType.STRING)
                        .optional()
                        .description("서버가 기록한 UTC 생성 시각. V14 이전 기록은 null")
        };
    }

    private FieldDescriptor[] errorResponseFields() {
        return new FieldDescriptor[]{
                fieldWithPath("code").description("안정적인 오류 코드"),
                fieldWithPath("message").description("사용자에게 표시할 오류 설명")
        };
    }

    private FieldDescriptor requestField(Class<?> requestType, String path, String description) {
        return new ConstrainedFields(requestType).withPath(path).description(description);
    }

    private FieldDescriptor optionalRequestField(Class<?> requestType, String path, String description) {
        return requestField(requestType, path, description).optional();
    }

    private FieldDescriptor requestStringArrayField(
            Class<?> requestType,
            String beanProperty,
            String path,
            String description
    ) {
        FieldDescriptor descriptor = fieldWithPath(path)
                .type(JsonFieldType.ARRAY)
                .description(description)
                .attributes(key("itemsType").value("STRING"));
        FieldDescriptor constrainedDescriptor =
                new ConstrainedFields(requestType).addConstraints(descriptor, beanProperty);
        return addStringArrayItemLengthConstraint(constrainedDescriptor, requestType, beanProperty);
    }

    private FieldDescriptor addStringArrayItemLengthConstraint(
            FieldDescriptor descriptor,
            Class<?> requestType,
            String beanProperty
    ) {
        if (!requestType.isRecord()) {
            throw new IllegalArgumentException(requestType.getSimpleName() + "은 record 요청 타입이 아닙니다.");
        }
        RecordComponent recordComponent = Arrays.stream(requestType.getRecordComponents())
                .filter(component -> component.getName().equals(beanProperty))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        requestType.getSimpleName() + "에 " + beanProperty + " 필드가 없습니다."
                ));
        if (!(recordComponent.getAnnotatedType() instanceof AnnotatedParameterizedType parameterizedType)) {
            return descriptor;
        }

        AnnotatedType itemType = parameterizedType.getAnnotatedActualTypeArguments()[0];
        if (!itemType.getType().equals(String.class)) {
            return descriptor;
        }
        Size itemSize = itemType.getAnnotation(Size.class);
        if (itemSize == null) {
            return descriptor;
        }

        List<Constraint> constraints = new ArrayList<>();
        Object existingConstraints = descriptor.getAttributes().get("validationConstraints");
        if (existingConstraints instanceof List<?> values) {
            values.forEach(value -> {
                if (!(value instanceof Constraint constraint)) {
                    throw new IllegalStateException("알 수 없는 REST Docs validation constraint입니다.");
                }
                constraints.add(constraint);
            });
        }

        int minLength = itemType.isAnnotationPresent(NotBlank.class)
                ? Math.max(1, itemSize.min())
                : itemSize.min();
        constraints.add(new Constraint(
                "org.hibernate.validator.constraints.Length",
                Map.of("min", minLength, "max", itemSize.max())
        ));
        return descriptor.attributes(key("validationConstraints").value(constraints));
    }

    private FieldDescriptor requestEnumField(
            Class<?> requestType,
            Class<? extends Enum<?>> enumType,
            String path,
            String description
    ) {
        FieldDescriptor descriptor = new EnumFields(enumType).withPath(path).description(description);
        return new ConstrainedFields(requestType).addConstraints(descriptor, path);
    }

    private FieldDescriptor stringArrayField(String path, String description) {
        return fieldWithPath(path)
                .type(JsonFieldType.ARRAY)
                .description(description)
                .attributes(key("itemsType").value("STRING"));
    }

    private FieldDescriptor enumField(Class<? extends Enum<?>> enumType, String path, String description) {
        return new EnumFields(enumType).withPath(path).description(description);
    }

    private record OperationDocumentation(String summary, String description) {
    }
}
