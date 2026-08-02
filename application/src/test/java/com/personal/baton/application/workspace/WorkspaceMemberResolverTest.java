package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Member;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("policy")
class WorkspaceMemberResolverTest {

    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TEAM_ID =
            UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID FIRST_MEMBER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333331");
    private static final UUID SECOND_MEMBER_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333332");

    @DisplayName("새 참조의 구성원 식별자는 중복과 빈 값을 제거하고 UUID 순서로 공유 잠금한다")
    @Test
    void normalizesMemberIdsBeforeSharedLock() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        Member first = member(FIRST_MEMBER_ID, TEAM_ID, "박민서");
        Member second = member(SECOND_MEMBER_ID, TEAM_ID, "김준호");
        when(repository.findMembersByTeamIdAndIdsWithSharedLock(
                TEAM_ID,
                List.of(FIRST_MEMBER_ID, SECOND_MEMBER_ID)
        )).thenReturn(List.of(second, first));

        Map<UUID, Member> members = new WorkspaceMemberResolver(repository)
                .requireActiveMembersForNewReferences(
                        TEAM_ID,
                        SECOND_MEMBER_ID,
                        null,
                        FIRST_MEMBER_ID,
                        SECOND_MEMBER_ID
                );

        assertThat(members).containsOnlyKeys(FIRST_MEMBER_ID, SECOND_MEMBER_ID);
        assertThat(members.get(FIRST_MEMBER_ID)).isSameAs(first);
        assertThat(members.get(SECOND_MEMBER_ID)).isSameAs(second);
        verify(repository).findMembersByTeamIdAndIdsWithSharedLock(
                TEAM_ID,
                List.of(FIRST_MEMBER_ID, SECOND_MEMBER_ID)
        );
    }

    @DisplayName("새 참조에 구성원이 없으면 공유 잠금 조회를 생략한다")
    @Test
    void skipsSharedLockForEmptyCandidates() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);

        Map<UUID, Member> members = new WorkspaceMemberResolver(repository)
                .requireActiveMembersForNewReferences(TEAM_ID, null, null);

        assertThat(members).isEmpty();
        verifyNoInteractions(repository);
    }

    @DisplayName("공유 잠금 결과에서 요청한 구성원이 빠지면 찾을 수 없음으로 거절한다")
    @Test
    void rejectsMissingLockedMember() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        when(repository.findMembersByTeamIdAndIdsWithSharedLock(
                TEAM_ID,
                List.of(FIRST_MEMBER_ID, SECOND_MEMBER_ID)
        )).thenReturn(List.of(member(FIRST_MEMBER_ID, TEAM_ID, "박민서")));
        WorkspaceMemberResolver resolver = new WorkspaceMemberResolver(repository);

        assertThatThrownBy(() -> resolver.requireActiveMembersForNewReferences(
                TEAM_ID,
                FIRST_MEMBER_ID,
                SECOND_MEMBER_ID
        ))
                .isInstanceOfSatisfying(
                        WorkspaceNotFoundException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("MEMBER_NOT_FOUND")
                );
    }

    @DisplayName("활동 종료 구성원은 공유 잠금 뒤 새 참조 대상으로 거절한다")
    @Test
    void rejectsDeactivatedMemberAfterSharedLock() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        Member deactivated = member(FIRST_MEMBER_ID, TEAM_ID, "박민서");
        deactivated.updateDeactivation(true, Instant.parse("2026-07-31T12:00:00Z"));
        when(repository.findMembersByTeamIdAndIdsWithSharedLock(
                TEAM_ID,
                List.of(FIRST_MEMBER_ID)
        )).thenReturn(List.of(deactivated));
        WorkspaceMemberResolver resolver = new WorkspaceMemberResolver(repository);

        assertThatThrownBy(() -> resolver.requireActiveMembersForNewReferences(
                TEAM_ID,
                FIRST_MEMBER_ID
        ))
                .isInstanceOf(DomainValidationException.class)
                .hasMessage("비활성 구성원은 새 담당자나 결정 작성자로 지정할 수 없습니다");
    }

    @DisplayName("다른 팀 소속 구성원은 단일 조회에서도 찾을 수 없음으로 숨긴다")
    @Test
    void hidesMemberFromAnotherTeam() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        when(repository.findMemberById(FIRST_MEMBER_ID))
                .thenReturn(Optional.of(member(FIRST_MEMBER_ID, OTHER_TEAM_ID, "박민서")));
        WorkspaceMemberResolver resolver = new WorkspaceMemberResolver(repository);

        assertThatThrownBy(() -> resolver.requireMember(TEAM_ID, FIRST_MEMBER_ID))
                .isInstanceOfSatisfying(
                        WorkspaceNotFoundException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("MEMBER_NOT_FOUND")
                );
    }

    private Member member(UUID memberId, UUID teamId, String name) {
        return Member.create(memberId, teamId, name);
    }
}
