package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.WorkspaceContract;

import com.personal.baton.application.calendar.CalendarChangeRecorder;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.CreateWorkspaceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CreatedWorkspaceResult;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.workspace.Team;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("policy")
class WorkspaceCreationCoordinatorTest {

    private static final String IDEMPOTENCY_KEY =
            "workspace-idempotency-primary-000001";
    private static final String IDEMPOTENCY_HASH =
            "8d1e24b26abdbc5bf8957a3b09865e1446ce5676acc63d394c3b5933bfb9d91b";
    private static final String REQUEST_FINGERPRINT =
            "f33384b00b45068094a6e980112fb4cbdacb8b245adab12044c522dfe6e5361c";

    @DisplayName("워크스페이스 생성 해시와 구성원 순서 정규화 지문은 저장 호환 벡터를 유지한다")
    @Test
    void preservesWorkspaceCreationCompatibilityVectors() {
        WorkspaceAccessRepository accessRepository = mock(WorkspaceAccessRepository.class);
        WorkspaceSeasonRepository seasonRepository = mock(WorkspaceSeasonRepository.class);
        WorkspacePeopleRepository peopleRepository = mock(WorkspacePeopleRepository.class);
        when(accessRepository.findTeamByIdempotencyKeyHash(IDEMPOTENCY_HASH))
                .thenReturn(Optional.empty());
        WorkspaceCreationCoordinator coordinator = new WorkspaceCreationCoordinator(
                accessRepository,
                seasonRepository,
                peopleRepository,
                new WorkspaceAccessControl(new WorkspaceSecrets("", "")),
                mock(CalendarChangeRecorder.class)
        );

        CreatedWorkspaceResult created = coordinator.create(
                IDEMPOTENCY_KEY,
                new CreateWorkspaceCommand(
                        "알고리즘 한 바퀴",
                        "2026 여름 시즌",
                        LocalDate.of(2026, 7, 2),
                        LocalDate.of(2026, 9, 17),
                        List.of("박민서", "김준호")
                )
        );

        ArgumentCaptor<Team> teamCaptor = ArgumentCaptor.forClass(Team.class);
        verify(accessRepository).saveTeam(teamCaptor.capture());
        Team savedTeam = teamCaptor.getValue();
        assertThat(savedTeam.getId()).isEqualTo(created.teamId());
        assertThat(savedTeam.getIdempotencyKeyHash()).isEqualTo(IDEMPOTENCY_HASH);
        assertThat(savedTeam.getCreationRequestFingerprint()).isEqualTo(REQUEST_FINGERPRINT);
        assertThat(savedTeam.getCreationSeasonId()).isEqualTo(created.seasonId());
        assertThat(created.accessKey())
                .isEqualTo("sIRgg9bOEBilomDeDJOnSuuTeOUtJXDYkKY5ePxEOAU");
    }
}
