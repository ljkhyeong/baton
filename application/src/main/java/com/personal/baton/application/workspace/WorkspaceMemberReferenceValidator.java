package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Member;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class WorkspaceMemberReferenceValidator {

    private final WorkspaceRepository repository;

    WorkspaceMemberReferenceValidator(WorkspaceRepository repository) {
        this.repository = repository;
    }

    Map<UUID, Member> requireActive(UUID teamId, UUID... candidateMemberIds) {
        List<UUID> memberIds = new ArrayList<>();
        for (UUID memberId : candidateMemberIds) {
            if (memberId != null && !memberIds.contains(memberId)) {
                memberIds.add(memberId);
            }
        }
        memberIds.sort(UUID::compareTo);
        if (memberIds.isEmpty()) {
            return Map.of();
        }

        List<Member> members = repository.findMembersByTeamIdAndIdsWithSharedLock(
                teamId,
                memberIds
        );
        Map<UUID, Member> membersById = indexMembers(members);
        for (UUID memberId : memberIds) {
            Member member = membersById.get(memberId);
            if (member == null) {
                throw notFound("MEMBER_NOT_FOUND", "구성원을 찾을 수 없습니다");
            }
            if (!member.isActive()) {
                throw new DomainValidationException(
                        "비활성 구성원은 새 담당자나 결정 작성자로 지정할 수 없습니다"
                );
            }
        }
        return membersById;
    }

    private Map<UUID, Member> indexMembers(List<Member> members) {
        Map<UUID, Member> result = new HashMap<>();
        for (Member member : members) {
            result.put(member.getId(), member);
        }
        return result;
    }

    private WorkspaceNotFoundException notFound(String code, String message) {
        return new WorkspaceNotFoundException(code, message);
    }
}
