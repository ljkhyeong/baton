package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Member;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
final class WorkspaceMemberResolver {

    private final WorkspacePeopleRepository repository;

    WorkspaceMemberResolver(WorkspacePeopleRepository repository) {
        this.repository = repository;
    }

    Member requireMember(UUID teamId, UUID memberId) {
        return repository.findMemberById(memberId)
                .filter(member -> member.getTeamId().equals(teamId))
                .orElseThrow(this::memberNotFound);
    }

    Map<UUID, Member> requireActiveMembersForNewReferences(
            UUID teamId,
            UUID... candidateMemberIds
    ) {
        List<UUID> memberIds = normalizedMemberIds(candidateMemberIds);
        if (memberIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Member> membersById = repository
                .findMembersByTeamIdAndIdsWithSharedLock(teamId, memberIds)
                .stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
        for (UUID memberId : memberIds) {
            Member member = membersById.get(memberId);
            if (member == null) {
                throw memberNotFound();
            }
            if (!member.isActive()) {
                throw new DomainValidationException(
                        "비활성 구성원은 새 담당자나 결정 작성자로 지정할 수 없습니다"
                );
            }
        }
        return membersById;
    }

    private List<UUID> normalizedMemberIds(UUID... candidateMemberIds) {
        return Arrays.stream(candidateMemberIds)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    private WorkspaceNotFoundException memberNotFound() {
        return new WorkspaceNotFoundException(
                "MEMBER_NOT_FOUND",
                "구성원을 찾을 수 없습니다"
        );
    }
}
