package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.IdempotencyKeyReusedException;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.domain.workspace.ContentCreationIdempotency;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.Member;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("policy")
class WorkspaceContentIdempotencyTest {

    private static final UUID TEAM_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEASON_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RESOURCE_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String IDEMPOTENCY_KEY = "workspace-idempotency-primary-000001";
    private static final String IDEMPOTENCY_HASH =
            "2908129627791efa4f59ef845b072d4b00979ea46605425b71ca68bb3938d3be";
    private static final String MEMBER_FINGERPRINT =
            "11ba4c8541a5f2695a80f28618559112fb9ee12c61f79e132651efcd93f0ff43";

    @DisplayName("콘텐츠 멱등 해시와 정규화 요청 지문은 고정 벡터와 같다")
    @Test
    void preservesContentIdempotencyCompatibilityVectors() {
        WorkspaceAccessRepository repository = mock(WorkspaceAccessRepository.class);
        when(repository.findContentCreationIdempotency(TEAM_ID, IDEMPOTENCY_HASH))
                .thenReturn(Optional.empty());
        WorkspaceContentIdempotency idempotency =
                new WorkspaceContentIdempotency(repository);
        Member member = Member.create(RESOURCE_ID, TEAM_ID, "  김민지  ");

        ContentCreationAttempt attempt = idempotency.prepare(
                TEAM_ID,
                SEASON_ID,
                ContentCreationOperation.MEMBER,
                IDEMPOTENCY_KEY,
                RESOURCE_ID,
                member.getName()
        );

        assertThat(attempt.replayResourceId()).isNull();
        assertThat(attempt.reservation().getTeamId()).isEqualTo(TEAM_ID);
        assertThat(attempt.reservation().getSeasonId()).isEqualTo(SEASON_ID);
        assertThat(attempt.reservation().getOperation())
                .isEqualTo(ContentCreationOperation.MEMBER);
        assertThat(attempt.reservation().getIdempotencyHash()).isEqualTo(IDEMPOTENCY_HASH);
        assertThat(attempt.reservation().getRequestFingerprint())
                .isEqualTo(MEMBER_FINGERPRINT);
        assertThat(attempt.reservation().getResourceId()).isEqualTo(RESOURCE_ID);

        idempotency.reserve(attempt);

        verify(repository).saveContentCreationIdempotency(attempt.reservation());
    }

    @DisplayName("같은 범위와 요청 지문은 최초 리소스를 재생하고 다른 요청은 키 재사용으로 거절한다")
    @Test
    void replaysMatchingRequestAndRejectsDifferentFingerprint() {
        WorkspaceAccessRepository repository = mock(WorkspaceAccessRepository.class);
        ContentCreationIdempotency existing = existingReservation(
                SEASON_ID,
                ContentCreationOperation.MEMBER
        );
        when(repository.findContentCreationIdempotency(TEAM_ID, IDEMPOTENCY_HASH))
                .thenReturn(Optional.of(existing));
        WorkspaceContentIdempotency idempotency =
                new WorkspaceContentIdempotency(repository);

        ContentCreationAttempt replay = idempotency.prepare(
                TEAM_ID,
                SEASON_ID,
                ContentCreationOperation.MEMBER,
                IDEMPOTENCY_KEY,
                UUID.randomUUID(),
                "김민지"
        );

        assertThat(replay.reservation()).isNull();
        assertThat(replay.replay(id -> Optional.of(id))).contains(RESOURCE_ID);
        assertThatThrownBy(() -> replay.replay(id -> Optional.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("멱등 생성된 MEMBER 리소스를 찾을 수 없습니다");
        assertThatThrownBy(() -> idempotency.prepare(
                TEAM_ID,
                SEASON_ID,
                ContentCreationOperation.MEMBER,
                IDEMPOTENCY_KEY,
                UUID.randomUUID(),
                "김민수"
        )).isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @DisplayName("저장된 멱등 기록의 시즌이나 작업 종류가 조회 범위와 다르면 내부 오류로 거절한다")
    @Test
    void rejectsPersistedScopeMismatch() {
        WorkspaceAccessRepository repository = mock(WorkspaceAccessRepository.class);
        ContentCreationIdempotency existing = existingReservation(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                ContentCreationOperation.ROLE
        );
        when(repository.findContentCreationIdempotency(TEAM_ID, IDEMPOTENCY_HASH))
                .thenReturn(Optional.of(existing));
        WorkspaceContentIdempotency idempotency =
                new WorkspaceContentIdempotency(repository);

        assertThatThrownBy(() -> idempotency.prepare(
                TEAM_ID,
                SEASON_ID,
                ContentCreationOperation.MEMBER,
                IDEMPOTENCY_KEY,
                UUID.randomUUID(),
                "김민지"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("저장된 콘텐츠 생성 멱등 범위가 요청과 일치하지 않습니다");
    }

    private ContentCreationIdempotency existingReservation(
            UUID seasonId,
            ContentCreationOperation operation
    ) {
        return ContentCreationIdempotency.create(
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                TEAM_ID,
                seasonId,
                operation,
                IDEMPOTENCY_HASH,
                MEMBER_FINGERPRINT,
                RESOURCE_ID
        );
    }
}
